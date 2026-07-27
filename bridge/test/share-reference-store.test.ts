import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
    createShareReferenceStore,
    ShareStateUnavailableError,
} from "../src/share-reference-store.js";

const roots: string[] = [];

async function tempStateDirectory(): Promise<string> {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "pi-mobile-share-state-"));
    roots.push(root);
    return root;
}

afterEach(async () => {
    await Promise.all(roots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true })));
});

describe("share reference store", () => {
    it("creates random stable references and persists them across restart", async () => {
        const stateDirectory = await tempStateDirectory();
        const firstStore = createShareReferenceStore({ stateDirectory });
        const first = await firstStore.getOrCreate("stable-session-id");
        expect(first).toMatch(/^[A-Za-z0-9_-]{22}$/);
        expect(await firstStore.getOrCreate("stable-session-id")).toBe(first);

        const restarted = createShareReferenceStore({ stateDirectory });
        expect(await restarted.resolve(first)).toBe("stable-session-id");
        expect(await restarted.getOrCreate("stable-session-id")).toBe(first);

        const state = JSON.parse(await fs.readFile(path.join(stateDirectory, "share-references.json"), "utf8"));
        expect(state).toMatchObject({ version: 1 });
        expect(JSON.stringify(state)).not.toContain("/tmp/");
        expect(JSON.stringify(state)).not.toContain("token");
        if (process.platform !== "win32") {
            expect((await fs.stat(stateDirectory)).mode & 0o777).toBe(0o700);
            expect((await fs.stat(path.join(stateDirectory, "share-references.json"))).mode & 0o777).toBe(0o600);
        }
    });

    it("serializes concurrent creates into one mapping", async () => {
        const stateDirectory = await tempStateDirectory();
        const store = createShareReferenceStore({ stateDirectory });
        const references = await Promise.all(Array.from({ length: 25 }, () => store.getOrCreate("one-session")));
        expect(new Set(references).size).toBe(1);
        const state = JSON.parse(await fs.readFile(path.join(stateDirectory, "share-references.json"), "utf8"));
        expect(state.mappings).toHaveLength(1);
    });

    it("retries collisions and revoke makes regeneration durable", async () => {
        const stateDirectory = await tempStateDirectory();
        const candidates = [
            "AAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAA",
            "BBBBBBBBBBBBBBBBBBBBBB",
        ];
        const store = createShareReferenceStore({
            stateDirectory,
            randomReference: () => candidates.shift() ?? "CCCCCCCCCCCCCCCCCCCCCC",
        });
        const first = await store.getOrCreate("session-a");
        const second = await store.getOrCreate("session-b");
        expect(first).toBe("AAAAAAAAAAAAAAAAAAAAAA");
        expect(second).toBe("BBBBBBBBBBBBBBBBBBBBBB");
        expect(await store.revoke("session-a")).toBe(true);
        expect(await store.resolve(first)).toBeUndefined();
        const replacement = await store.getOrCreate("session-a");
        expect(replacement).toBe("CCCCCCCCCCCCCCCCCCCCCC");

        const restarted = createShareReferenceStore({ stateDirectory });
        expect(await restarted.resolve(first)).toBeUndefined();
        expect(await restarted.resolve(replacement)).toBe("session-a");
    });

    it("recovers a complete interrupted temporary write only when primary is absent", async () => {
        const stateDirectory = await tempStateDirectory();
        await fs.mkdir(stateDirectory, { recursive: true });
        const reference = "DDDDDDDDDDDDDDDDDDDDDD";
        await fs.writeFile(path.join(stateDirectory, "share-references.json.tmp"), JSON.stringify({
            version: 1,
            mappings: [{
                sessionId: "recoverable-session",
                shareReference: reference,
                createdAt: "2026-01-01T00:00:00.000Z",
            }],
        }));
        const store = createShareReferenceStore({ stateDirectory });
        expect(await store.resolve(reference)).toBe("recoverable-session");
        await expect(fs.stat(path.join(stateDirectory, "share-references.json"))).resolves.toBeDefined();
    });

    it.each([
        "{broken-json",
        JSON.stringify({ version: 999, mappings: [] }),
        JSON.stringify({ version: 1, mappings: [{ sessionId: "bad id", shareReference: "EEEEEEEEEEEEEEEEEEEEEE", createdAt: "now" }] }),
    ])("fails closed without discarding corrupt or unsupported state", async (content) => {
        const stateDirectory = await tempStateDirectory();
        await fs.mkdir(stateDirectory, { recursive: true });
        const primary = path.join(stateDirectory, "share-references.json");
        await fs.writeFile(primary, content);
        const store = createShareReferenceStore({ stateDirectory });
        await expect(store.resolve("EEEEEEEEEEEEEEEEEEEEEE")).rejects.toBeInstanceOf(ShareStateUnavailableError);
        expect(await fs.readFile(primary, "utf8")).toBe(content);
    });

    it("rejects an oversized state file before parsing", async () => {
        const stateDirectory = await tempStateDirectory();
        await fs.mkdir(stateDirectory, { recursive: true });
        const primary = path.join(stateDirectory, "share-references.json");
        const handle = await fs.open(primary, "w");
        await handle.truncate(16 * 1024 * 1024 + 1);
        await handle.close();
        const store = createShareReferenceStore({ stateDirectory });
        await expect(store.getOrCreate("session-id")).rejects.toBeInstanceOf(ShareStateUnavailableError);
    });
});
