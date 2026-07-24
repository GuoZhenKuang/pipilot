import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import readline from "node:readline";
import type { Readable } from "node:stream";
import { StringDecoder } from "node:string_decoder";

import type { Logger } from "pino";

export interface PiRpcForwarderMessage {
    [key: string]: unknown;
}

export interface PiRpcForwarderLifecycleEvent {
    type: "start" | "exit";
    pid?: number;
    code?: number | null;
    signal?: NodeJS.Signals | null;
    restartAttempt?: number;
}

export interface PiRpcForwarder {
    setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void;
    setLifecycleHandler(handler: (event: PiRpcForwarderLifecycleEvent) => void): void;
    send(payload: Record<string, unknown>): void;
    stop(): Promise<void>;
}

export interface PiRpcForwarderConfig {
    command: string;
    args: string[];
    cwd: string;
    env?: NodeJS.ProcessEnv;
    restartBaseDelayMs?: number;
    maxRestartDelayMs?: number;
}

const DEFAULT_RESTART_BASE_DELAY_MS = 250;
const DEFAULT_MAX_RESTART_DELAY_MS = 5_000;

export function createPiRpcForwarder(config: PiRpcForwarderConfig, logger: Logger): PiRpcForwarder {
    const restartBaseDelayMs = config.restartBaseDelayMs ?? DEFAULT_RESTART_BASE_DELAY_MS;
    const maxRestartDelayMs = config.maxRestartDelayMs ?? DEFAULT_MAX_RESTART_DELAY_MS;

    let processRef: ChildProcessWithoutNullStreams | undefined;
    let stdoutCleanup: (() => void) | undefined;
    let stderrReader: readline.Interface | undefined;
    let restartTimer: NodeJS.Timeout | undefined;

    let messageHandler: (payload: PiRpcForwarderMessage) => void = () => {};
    let lifecycleHandler: (event: PiRpcForwarderLifecycleEvent) => void = () => {};

    let shouldRun = true;
    let keepingAlive = false;
    let restartAttempt = 0;

    const cleanup = (): void => {
        stdoutCleanup?.();
        stdoutCleanup = undefined;
        stderrReader?.close();
        stderrReader = undefined;
        processRef = undefined;
    };

    const scheduleRestart = (): void => {
        if (restartTimer || !shouldRun || !keepingAlive) {
            return;
        }

        restartAttempt += 1;
        const delayMs = Math.min(restartBaseDelayMs * 2 ** (restartAttempt - 1), maxRestartDelayMs);

        logger.warn(
            {
                restartAttempt,
                delayMs,
            },
            "Scheduling pi RPC subprocess restart",
        );

        restartTimer = setTimeout(() => {
            restartTimer = undefined;
            if (!shouldRun || !keepingAlive) return;

            try {
                startProcess();
            } catch {
                logger.error("Failed to restart pi RPC subprocess");
                scheduleRestart();
            }
        }, delayMs);
    };

    const startProcess = (): ChildProcessWithoutNullStreams => {
        if (!shouldRun) {
            throw new Error("Cannot start stopped RPC forwarder");
        }

        if (processRef && !processRef.killed) {
            return processRef;
        }

        const child = spawn(config.command, config.args, {
            cwd: config.cwd,
            env: config.env ?? process.env,
            stdio: "pipe",
        });

        child.on("error", () => {
            logger.error("pi RPC subprocess error");
        });

        child.on("exit", (code, signal) => {
            logger.info({ code, signal }, "pi RPC subprocess exited");
            cleanup();

            lifecycleHandler({
                type: "exit",
                code,
                signal,
                restartAttempt,
            });

            scheduleRestart();
        });

        stdoutCleanup = attachJsonlReader(child.stdout, (line) => {
            const parsedMessage = tryParseJsonObject(line);
            if (!parsedMessage) {
                logger.warn(
                    {
                        lineLength: line.length,
                    },
                    "Dropping invalid JSON from pi RPC stdout",
                );
                return;
            }

            messageHandler(parsedMessage);
        });

        stderrReader = readline.createInterface({
            input: child.stderr,
            crlfDelay: Infinity,
        });
        stderrReader.on("line", (line) => {
            logger.warn({ lineLength: line.length }, "pi RPC stderr");
        });

        processRef = child;
        restartAttempt = 0;

        logger.info("Started pi RPC subprocess");

        lifecycleHandler({
            type: "start",
            pid: child.pid,
            restartAttempt,
        });

        return child;
    };

    return {
        setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void {
            messageHandler = handler;
        },
        setLifecycleHandler(handler: (event: PiRpcForwarderLifecycleEvent) => void): void {
            lifecycleHandler = handler;
        },
        send(payload: Record<string, unknown>): void {
            keepingAlive = true;
            const child = startProcess();

            const serializedPayload = `${JSON.stringify(payload)}\n`;
            const writeOk = child.stdin.write(serializedPayload);

            if (!writeOk) {
                logger.warn("pi RPC stdin backpressure detected");
            }
        },
        async stop(): Promise<void> {
            shouldRun = false;
            keepingAlive = false;
            restartAttempt = 0;

            if (restartTimer) {
                clearTimeout(restartTimer);
                restartTimer = undefined;
            }

            const child = processRef;
            if (!child) return;

            child.stdin.end();

            if (child.killed) {
                cleanup();
                return;
            }

            await new Promise<void>((resolve) => {
                const timer = setTimeout(() => {
                    child.kill("SIGKILL");
                }, 2_000);

                child.once("exit", () => {
                    clearTimeout(timer);
                    resolve();
                });

                child.kill("SIGTERM");
            });
        },
    };
}

function attachJsonlReader(
    stream: Readable,
    onLine: (line: string) => void,
): () => void {
    const decoder = new StringDecoder("utf8");
    let buffer = "";

    const flushBuffer = (): void => {
        while (true) {
            const newlineIndex = buffer.indexOf("\n");
            if (newlineIndex === -1) {
                return;
            }

            let line = buffer.slice(0, newlineIndex);
            buffer = buffer.slice(newlineIndex + 1);

            if (line.endsWith("\r")) {
                line = line.slice(0, -1);
            }

            onLine(line);
        }
    };

    const handleData = (chunk: string | Buffer): void => {
        buffer += typeof chunk === "string" ? chunk : decoder.write(chunk);
        flushBuffer();
    };

    const handleEnd = (): void => {
        buffer += decoder.end();

        if (!buffer) {
            return;
        }

        const trailingLine = buffer.endsWith("\r") ? buffer.slice(0, -1) : buffer;
        buffer = "";
        onLine(trailingLine);
    };

    stream.on("data", handleData);
    stream.on("end", handleEnd);

    return () => {
        stream.off("data", handleData);
        stream.off("end", handleEnd);
    };
}

function tryParseJsonObject(value: string): Record<string, unknown> | undefined {
    let parsed: unknown;

    try {
        parsed = JSON.parse(value);
    } catch {
        return undefined;
    }

    if (!isRecord(parsed)) {
        return undefined;
    }

    return parsed;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
