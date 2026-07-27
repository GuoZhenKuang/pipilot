import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";
import { WebSocket, type RawData } from "ws";

import type { BridgeConfig } from "../src/config.js";
import { createLogger } from "../src/logger.js";
import type { BridgeServer } from "../src/server.js";
import { createBridgeServer } from "../src/server.js";

const roots: string[] = [];
let activeServer: BridgeServer | undefined;

afterEach(async () => {
    await activeServer?.stop();
    activeServer = undefined;
    await Promise.all(roots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true })));
});

describe("authenticated session sharing", () => {
    it("creates, resolves across a moved file, revokes, and regenerates without exposing metadata", async () => {
        const fixture = await startFixture();
        activeServer = fixture.server;
        const ws = await connect(fixture.wsUrl, true);

        const created = await request(ws, {
            type: "bridge_get_or_create_session_share",
            sessionPath: fixture.sessionPath,
        }, "bridge_session_share");
        const reference = created.shareReference as string;
        expect(reference).toMatch(/^[A-Za-z0-9_-]{22}$/);
        expect(created.webUrl).toBe(`https://share.example.test/s/v1/${reference}`);
        expect(JSON.stringify(created)).not.toContain(fixture.sessionId);
        expect(JSON.stringify(created)).not.toContain(fixture.sessionPath);

        const repeated = await request(ws, {
            type: "bridge_get_or_create_session_share",
            sessionPath: fixture.sessionPath,
        }, "bridge_session_share");
        expect(repeated.shareReference).toBe(reference);

        const movedPath = path.join(path.dirname(fixture.sessionPath), "moved-session.jsonl");
        await fs.rename(fixture.sessionPath, movedPath);
        const resolved = await request(ws, {
            type: "bridge_resolve_session_share",
            shareReference: reference,
        }, "bridge_session_share_resolved");
        expect((resolved.session as Record<string, unknown>).sessionPath).toBe(movedPath);

        await request(ws, {
            type: "bridge_revoke_session_share",
            sessionPath: movedPath,
        }, "bridge_session_share_revoked");
        const unavailable = await request(ws, {
            type: "bridge_resolve_session_share",
            shareReference: reference,
        }, "bridge_error");
        expect(unavailable.code).toBe("share_not_found");
        expect(JSON.stringify(unavailable)).not.toContain(reference);

        const replacement = await request(ws, {
            type: "bridge_get_or_create_session_share",
            sessionPath: movedPath,
        }, "bridge_session_share");
        expect(replacement.shareReference).not.toBe(reference);
        ws.close();
    });

    it("never resolves duplicate live IDs arbitrarily and keeps list sessions usable", async () => {
        const fixture = await startFixture();
        activeServer = fixture.server;
        const duplicatePath = path.join(path.dirname(fixture.sessionPath), "duplicate.jsonl");
        await fs.copyFile(fixture.sessionPath, duplicatePath);
        const ws = await connect(fixture.wsUrl, true);

        const listed = await request(ws, { type: "bridge_list_sessions" }, "bridge_sessions");
        const groups = listed.groups as Array<{ sessions: Array<Record<string, unknown>> }>;
        expect(groups.flatMap((group) => group.sessions)).toHaveLength(2);
        expect(groups.flatMap((group) => group.sessions).every((session) => session.isSessionIdUnique === false)).toBe(true);

        const denied = await request(ws, {
            type: "bridge_get_or_create_session_share",
            sessionPath: fixture.sessionPath,
        }, "bridge_error");
        expect(denied.code).toBe("session_identity_ambiguous");
        ws.close();
    });

    it("keeps corrupt share state isolated and serves a host-header-independent landing page", async () => {
        const root = await tempRoot();
        const stateDirectory = path.join(root, "state");
        await fs.mkdir(stateDirectory, { recursive: true });
        await fs.writeFile(path.join(stateDirectory, "share-references.json"), "{corrupt");
        const fixture = await startFixture(root, stateDirectory);
        activeServer = fixture.server;
        const ws = await connect(fixture.wsUrl, true);

        const listed = await request(ws, { type: "bridge_list_sessions" }, "bridge_sessions");
        expect(listed.groups).toBeDefined();
        const failedShare = await request(ws, {
            type: "bridge_get_or_create_session_share",
            sessionPath: fixture.sessionPath,
        }, "bridge_error");
        expect(failedShare.code).toBe("share_state_unavailable");

        const reference = "AbCdEfGhIjKlMnOpQrStUv";
        const response = await fetch(`${fixture.httpUrl}/s/v1/${reference}`, {
            headers: { host: "attacker.invalid" },
        });
        expect(response.status).toBe(200);
        expect(response.headers.get("cache-control")).toBe("no-store");
        expect(response.headers.get("referrer-policy")).toBe("no-referrer");
        const page = await response.text();
        expect(page).toContain("host=share.example.test");
        expect(page).not.toContain("attacker.invalid");
        expect(page).not.toContain(fixture.sessionId);
        ws.close();
    });
});

async function startFixture(existingRoot?: string, explicitStateDirectory?: string): Promise<{
    server: BridgeServer;
    wsUrl: string;
    httpUrl: string;
    sessionPath: string;
    sessionId: string;
}> {
    const root = existingRoot ?? await tempRoot();
    const sessionsDirectory = path.join(root, "sessions");
    const projectDirectory = path.join(sessionsDirectory, "--project--");
    const stateDirectory = explicitStateDirectory ?? path.join(root, "state");
    await fs.mkdir(projectDirectory, { recursive: true });
    const sessionId = "documented-internal-session-id";
    const sessionPath = path.join(projectDirectory, "session.jsonl");
    await fs.writeFile(sessionPath, [
        JSON.stringify({
            type: "session", version: 3, id: sessionId,
            timestamp: "2026-01-01T00:00:00.000Z", cwd: "/synthetic/project",
        }),
        JSON.stringify({
            type: "message", id: "m1", parentId: null,
            timestamp: "2026-01-01T00:00:01.000Z", message: { role: "user", content: "private transcript" },
        }),
    ].join("\n"));

    const config: BridgeConfig = {
        host: "127.0.0.1", port: 0, logLevel: "silent", authToken: "test-token",
        processIdleTtlMs: 300_000, reconnectGraceMs: 0, sessionDirectory: sessionsDirectory, stateDirectory,
        shareOrigin: "https://share.example.test", enableHealthEndpoint: true,
        websocketMaxPayloadBytes: 16 * 1024 * 1024, importMaxBytes: 10 * 1024 * 1024, piCommand: "pi",
    };
    const server = createBridgeServer(config, createLogger("silent"), { probePiVersion: async () => "0.80.6" });
    const info = await server.start();
    return {
        server,
        wsUrl: `ws://127.0.0.1:${info.port}/ws`,
        httpUrl: `http://127.0.0.1:${info.port}`,
        sessionPath,
        sessionId,
    };
}

async function tempRoot(): Promise<string> {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "pi-mobile-share-server-"));
    roots.push(root);
    return root;
}

const buffers = new WeakMap<WebSocket, Array<{ payload: Record<string, unknown> }>>();

async function connect(url: string, authenticated: boolean): Promise<WebSocket> {
    return await new Promise((resolve, reject) => {
        const ws = new WebSocket(url, authenticated ? { headers: { authorization: "Bearer test-token" } } : undefined);
        const messages: Array<{ payload: Record<string, unknown> }> = [];
        ws.on("message", (data: RawData) => messages.push(JSON.parse(data.toString())));
        ws.once("open", () => {
            buffers.set(ws, messages);
            resolve(ws);
        });
        ws.once("error", reject);
    });
}

async function request(
    ws: WebSocket,
    payload: Record<string, unknown>,
    expectedType: string,
): Promise<Record<string, unknown>> {
    const messages = buffers.get(ws) ?? [];
    const start = messages.length;
    ws.send(JSON.stringify({ channel: "bridge", payload }));
    const deadline = Date.now() + 2_000;
    while (Date.now() < deadline) {
        const match = messages.slice(start).find((message) => message.payload.type === expectedType);
        if (match) return match.payload;
        await new Promise((resolve) => setTimeout(resolve, 5));
    }
    throw new Error(`Timed out waiting for ${expectedType}`);
}
