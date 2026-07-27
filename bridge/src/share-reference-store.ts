import { randomBytes } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";

import { isValidShareReference } from "./share-links.js";
import { isValidSessionId } from "./session-indexer.js";

const STORE_VERSION = 1;
const MAX_STORE_BYTES = 16 * 1024 * 1024;
const MAX_MAPPINGS = 100_000;
const MAX_COLLISION_ATTEMPTS = 32;

interface StoredMapping {
    sessionId: string;
    shareReference: string;
    createdAt: string;
    revokedAt?: string;
}

interface StoredState {
    version: typeof STORE_VERSION;
    mappings: StoredMapping[];
}

export class ShareStateUnavailableError extends Error {
    readonly code = "share_state_unavailable";

    constructor(message = "Share reference state is unavailable; restore or repair the bridge state file") {
        super(message);
        this.name = "ShareStateUnavailableError";
    }
}

export interface ShareReferenceStore {
    getOrCreate(sessionId: string): Promise<string>;
    resolve(shareReference: string): Promise<string | undefined>;
    revoke(sessionId: string): Promise<boolean>;
}

export interface ShareReferenceStoreOptions {
    stateDirectory: string;
    randomReference?: () => string;
    now?: () => Date;
}

export function createShareReferenceStore(options: ShareReferenceStoreOptions): ShareReferenceStore {
    const stateDirectory = path.resolve(options.stateDirectory);
    const primaryPath = path.join(stateDirectory, "share-references.json");
    const temporaryPath = path.join(stateDirectory, "share-references.json.tmp");
    const randomReference = options.randomReference ?? (() => randomBytes(16).toString("base64url"));
    const now = options.now ?? (() => new Date());
    let statePromise: Promise<StoredState> | undefined;
    let writeQueue = Promise.resolve();

    const load = (): Promise<StoredState> => {
        statePromise ??= loadState(primaryPath, temporaryPath);
        return statePromise;
    };

    const serialize = async <T>(operation: () => Promise<T>): Promise<T> => {
        const result = writeQueue.then(operation, operation);
        writeQueue = result.then(() => undefined, () => undefined);
        return await result;
    };

    return {
        async getOrCreate(sessionId: string): Promise<string> {
            if (!isValidSessionId(sessionId)) throw new Error("Invalid session identity");
            return await serialize(async () => {
                const state = await load();
                const existing = state.mappings.find((mapping) =>
                    mapping.sessionId === sessionId && mapping.revokedAt === undefined);
                if (existing) return existing.shareReference;
                if (state.mappings.length >= MAX_MAPPINGS) {
                    throw new ShareStateUnavailableError("Share reference store reached its mapping limit");
                }

                const knownReferences = new Set(state.mappings.map((mapping) => mapping.shareReference));
                let shareReference: string | undefined;
                for (let attempt = 0; attempt < MAX_COLLISION_ATTEMPTS; attempt += 1) {
                    const candidate = randomReference();
                    if (isValidShareReference(candidate) && !knownReferences.has(candidate)) {
                        shareReference = candidate;
                        break;
                    }
                }
                if (!shareReference) throw new ShareStateUnavailableError("Could not allocate a share reference");

                const next: StoredState = {
                    version: STORE_VERSION,
                    mappings: [...state.mappings, {
                        sessionId,
                        shareReference,
                        createdAt: now().toISOString(),
                    }],
                };
                await persistState(stateDirectory, primaryPath, temporaryPath, next);
                statePromise = Promise.resolve(next);
                return shareReference;
            });
        },

        async resolve(shareReference: string): Promise<string | undefined> {
            if (!isValidShareReference(shareReference)) return undefined;
            const state = await load();
            return state.mappings.find((mapping) =>
                mapping.shareReference === shareReference && mapping.revokedAt === undefined)?.sessionId;
        },

        async revoke(sessionId: string): Promise<boolean> {
            if (!isValidSessionId(sessionId)) throw new Error("Invalid session identity");
            return await serialize(async () => {
                const state = await load();
                const index = state.mappings.findIndex((mapping) =>
                    mapping.sessionId === sessionId && mapping.revokedAt === undefined);
                if (index < 0) return false;
                const mappings = state.mappings.slice();
                mappings[index] = { ...mappings[index], revokedAt: now().toISOString() };
                const next: StoredState = { version: STORE_VERSION, mappings };
                await persistState(stateDirectory, primaryPath, temporaryPath, next);
                statePromise = Promise.resolve(next);
                return true;
            });
        },
    };
}

async function loadState(primaryPath: string, temporaryPath: string): Promise<StoredState> {
    try {
        const primary = await readAndValidate(primaryPath);
        await fs.unlink(temporaryPath).catch((error: unknown) => {
            if (!isErrorCode(error, "ENOENT")) throw error;
        });
        return primary;
    } catch (error: unknown) {
        if (!isErrorCode(error, "ENOENT")) {
            throw unavailable(error);
        }
    }

    try {
        const recovered = await readAndValidate(temporaryPath);
        await fs.rename(temporaryPath, primaryPath);
        return recovered;
    } catch (error: unknown) {
        if (isErrorCode(error, "ENOENT")) return { version: STORE_VERSION, mappings: [] };
        throw unavailable(error);
    }
}

async function readAndValidate(filePath: string): Promise<StoredState> {
    const stats = await fs.stat(filePath);
    if (stats.size > MAX_STORE_BYTES) throw new Error("Share state exceeds its size limit");
    const parsed: unknown = JSON.parse(await fs.readFile(filePath, "utf8"));
    if (!isRecord(parsed) || parsed.version !== STORE_VERSION || !Array.isArray(parsed.mappings)) {
        throw new Error("Unsupported share state version or shape");
    }
    if (parsed.mappings.length > MAX_MAPPINGS) throw new Error("Share state exceeds its mapping limit");

    const sessionIds = new Set<string>();
    const references = new Set<string>();
    const mappings = parsed.mappings.map((raw): StoredMapping => {
        if (!isRecord(raw) || typeof raw.sessionId !== "string" || !isValidSessionId(raw.sessionId) ||
            !isValidShareReference(raw.shareReference) || typeof raw.createdAt !== "string" ||
            (raw.revokedAt !== undefined && typeof raw.revokedAt !== "string")) {
            throw new Error("Invalid share state mapping");
        }
        if (references.has(raw.shareReference)) throw new Error("Duplicate share reference in state");
        references.add(raw.shareReference);
        if (raw.revokedAt === undefined) {
            if (sessionIds.has(raw.sessionId)) throw new Error("Duplicate live session mapping in state");
            sessionIds.add(raw.sessionId);
        }
        return {
            sessionId: raw.sessionId,
            shareReference: raw.shareReference,
            createdAt: raw.createdAt,
            ...(raw.revokedAt === undefined ? {} : { revokedAt: raw.revokedAt }),
        };
    });
    return { version: STORE_VERSION, mappings };
}

async function persistState(
    stateDirectory: string,
    primaryPath: string,
    temporaryPath: string,
    state: StoredState,
): Promise<void> {
    const serialized = `${JSON.stringify(state)}\n`;
    if (Buffer.byteLength(serialized, "utf8") > MAX_STORE_BYTES) {
        throw new ShareStateUnavailableError("Share reference state reached its size limit");
    }

    await fs.mkdir(stateDirectory, { recursive: true, mode: 0o700 });
    await enforceMode(stateDirectory, 0o700);
    const handle = await fs.open(temporaryPath, "w", 0o600);
    try {
        await handle.writeFile(serialized, "utf8");
        await handle.chmod(0o600).catch(ignoreUnsupportedPermissions);
        await handle.sync();
    } finally {
        await handle.close();
    }
    await fs.rename(temporaryPath, primaryPath);
    await enforceMode(primaryPath, 0o600);
    const directoryHandle = await fs.open(stateDirectory, "r");
    try {
        await directoryHandle.sync().catch(ignoreUnsupportedPermissions);
    } finally {
        await directoryHandle.close();
    }
}

async function enforceMode(target: string, mode: number): Promise<void> {
    await fs.chmod(target, mode).catch(ignoreUnsupportedPermissions);
}

function ignoreUnsupportedPermissions(error: unknown): void {
    if (isErrorCode(error, "ENOSYS") || isErrorCode(error, "EPERM") || isErrorCode(error, "EINVAL")) return;
    throw error;
}

function unavailable(error: unknown): ShareStateUnavailableError {
    return error instanceof ShareStateUnavailableError ? error : new ShareStateUnavailableError();
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isErrorCode(error: unknown, code: string): boolean {
    return isRecord(error) && error.code === code;
}
