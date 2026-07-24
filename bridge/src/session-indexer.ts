import { createHash } from "node:crypto";
import { createReadStream, type Dirent } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { StringDecoder } from "node:string_decoder";

import type { Logger } from "pino";

export interface SessionIndexEntry {
    sessionPath: string;
    cwd: string;
    createdAt: string;
    updatedAt: string;
    displayName?: string;
    firstUserMessagePreview?: string;
    messageCount: number;
    lastModel?: string;
}

export interface SessionIndexGroup {
    cwd: string;
    sessions: SessionIndexEntry[];
}

export interface SessionTreeEntry {
    entryId: string;
    parentId: string | null;
    entryType: string;
    role?: string;
    timestamp?: string;
    preview: string;
    label?: string;
    isBookmarked: boolean;
}

export type SessionTreeFilter = "default" | "all" | "no-tools" | "user-only" | "labeled-only";

export interface SessionTreeSnapshot {
    sessionPath: string;
    rootIds: string[];
    currentLeafId?: string;
    entries: SessionTreeEntry[];
}

export interface SessionFreshnessFingerprint {
    mtimeMs: number;
    sizeBytes: number;
    entryCount: number;
    lastEntryId?: string;
    lastEntriesHash: string;
}

export interface SessionFreshnessSnapshot {
    sessionPath: string;
    cwd: string;
    fingerprint: SessionFreshnessFingerprint;
}

export interface SessionIndexerMetrics {
    parseCacheHits: number;
    parseCacheMisses: number;
    inflightParseHits: number;
    fullFileReads: number;
    treeCacheHits: number;
    treeCacheMisses: number;
}

export interface SessionIndexer {
    listSessions(): Promise<SessionIndexGroup[]>;
    getSessionTree(sessionPath: string, filter?: SessionTreeFilter): Promise<SessionTreeSnapshot>;
    getSessionFreshness(sessionPath: string): Promise<SessionFreshnessSnapshot>;
    getMetrics?(): SessionIndexerMetrics;
}

export interface SessionIndexerOptions {
    sessionsDirectory: string;
    logger: Logger;
}

interface CachedSessionMetadata {
    mtimeMs: number;
    size: number;
    entry: SessionIndexEntry | undefined;
}

interface CachedSessionFreshness {
    mtimeMs: number;
    size: number;
    freshness: SessionFreshnessSnapshot;
    tailLines: string[];
    endedWithNewline: boolean;
}

interface ParsedSessionFile {
    mtimeMs: number;
    size: number;
    lines: string[];
    entries: Record<string, unknown>[];
    endedWithNewline: boolean;
}

interface ParsedFreshness {
    snapshot: SessionFreshnessSnapshot;
    tailLines: string[];
    endedWithNewline: boolean;
}

const MAX_PARSED_SESSION_CACHE_FILES = 16;
const MAX_PARSED_SESSION_CACHE_BYTES = 32 * 1024 * 1024;
const MAX_TREE_CACHE_ENTRIES = 32;
const MAX_INCREMENTAL_FRESHNESS_BYTES = 256 * 1024;

export function createSessionIndexer(options: SessionIndexerOptions): SessionIndexer {
    const sessionsRoot = path.resolve(options.sessionsDirectory);
    const sessionMetadataCache = new Map<string, CachedSessionMetadata>();
    const sessionFreshnessCache = new Map<string, CachedSessionFreshness>();
    // One parsed snapshot is shared by metadata/freshness/tree consumers. The key
    // is the canonical path plus the stat revision; mutation naturally invalidates it.
    const parsedSessionCache = new Map<string, ParsedSessionFile>();
    const parsedSessionInflight = new Map<string, Promise<ParsedSessionFile>>();
    const treeCache = new Map<string, SessionTreeSnapshot>();
    const metrics: SessionIndexerMetrics = {
        parseCacheHits: 0,
        parseCacheMisses: 0,
        inflightParseHits: 0,
        fullFileReads: 0,
        treeCacheHits: 0,
        treeCacheMisses: 0,
    };

    return {
        async listSessions(): Promise<SessionIndexGroup[]> {
            const sessionFiles = await findSessionFiles(sessionsRoot, options.logger);
            const sessions: SessionIndexEntry[] = [];

            const sessionFileSet = new Set(sessionFiles);
            for (const cachedPath of sessionMetadataCache.keys()) {
                if (!sessionFileSet.has(cachedPath)) {
                    sessionMetadataCache.delete(cachedPath);
                }
            }
            for (const cachedPath of sessionFreshnessCache.keys()) {
                if (!sessionFileSet.has(cachedPath)) {
                    sessionFreshnessCache.delete(cachedPath);
                }
            }

            const parsedEntries = await mapWithConcurrency(
                sessionFiles,
                4,
                (sessionFile) =>
                    parseSessionFileWithCache(
                        sessionFile,
                        options.logger,
                        sessionMetadataCache,
                        parsedSessionCache,
                        metrics,
                    ),
            );
            for (const entry of parsedEntries) {
                if (entry) sessions.push(entry);
            }

            const groups = new Map<string, SessionIndexEntry[]>();
            for (const session of sessions) {
                const byCwd = groups.get(session.cwd) ?? [];
                byCwd.push(session);
                groups.set(session.cwd, byCwd);
            }

            const groupedSessions: SessionIndexGroup[] = [];
            for (const [cwd, groupedEntries] of groups.entries()) {
                groupedEntries.sort((a, b) => compareIsoDesc(a.updatedAt, b.updatedAt));
                groupedSessions.push({ cwd, sessions: groupedEntries });
            }

            groupedSessions.sort((a, b) => a.cwd.localeCompare(b.cwd));

            return groupedSessions;
        },

        async getSessionTree(sessionPath: string, filter?: SessionTreeFilter): Promise<SessionTreeSnapshot> {
            const resolvedSessionPath = await resolveSessionPath(sessionPath, sessionsRoot);
            return parseSessionTreeFile(
                resolvedSessionPath,
                options.logger,
                filter,
                parsedSessionCache,
                treeCache,
                parsedSessionInflight,
                metrics,
            );
        },

        async getSessionFreshness(sessionPath: string): Promise<SessionFreshnessSnapshot> {
            const resolvedSessionPath = await resolveSessionPath(sessionPath, sessionsRoot);
            return parseSessionFreshnessWithCache(
                resolvedSessionPath,
                options.logger,
                sessionFreshnessCache,
                parsedSessionCache,
                parsedSessionInflight,
                metrics,
            );
        },

        getMetrics(): SessionIndexerMetrics {
            return { ...metrics };
        },
    };
}

async function mapWithConcurrency<T, R>(
    values: T[],
    concurrency: number,
    mapper: (value: T) => Promise<R>,
): Promise<R[]> {
    const results = new Array<R>(values.length);
    let nextIndex = 0;
    const worker = async (): Promise<void> => {
        while (true) {
            const index = nextIndex++;
            if (index >= values.length) return;
            results[index] = await mapper(values[index]);
        }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, worker));
    return results;
}

async function resolveSessionPath(sessionPath: string, sessionsRoot: string): Promise<string> {
    const resolvedSessionPath = path.resolve(sessionPath);

    if (!resolvedSessionPath.endsWith(".jsonl")) {
        throw new Error("Session path must point to a .jsonl file");
    }

    const realSessionsRoot = await resolveRealPathOrFallback(sessionsRoot);
    const realSessionPath = await resolveRealPathOrFallback(resolvedSessionPath);

    const isWithinSessionsRoot =
        realSessionPath === realSessionsRoot ||
        realSessionPath.startsWith(`${realSessionsRoot}${path.sep}`);

    if (!isWithinSessionsRoot) {
        throw new Error("Session path is outside configured session directory");
    }

    return resolvedSessionPath;
}

async function resolveRealPathOrFallback(filePath: string): Promise<string> {
    try {
        return await fs.realpath(filePath);
    } catch (error: unknown) {
        if (isErrorWithCode(error, "ENOENT")) {
            return path.resolve(filePath);
        }

        throw error;
    }
}

async function findSessionFiles(rootDir: string, logger: Logger): Promise<string[]> {
    let directoryEntries: Dirent[];

    try {
        directoryEntries = await fs.readdir(rootDir, { withFileTypes: true });
    } catch (error: unknown) {
        if (isErrorWithCode(error, "ENOENT")) {
            logger.warn("Session directory does not exist");
            return [];
        }

        throw error;
    }

    const sessionFiles: string[] = [];

    for (const directoryEntry of directoryEntries) {
        const absolutePath = path.join(rootDir, directoryEntry.name);

        if (directoryEntry.isDirectory()) {
            const nestedFiles = await findSessionFiles(absolutePath, logger);
            sessionFiles.push(...nestedFiles);
            continue;
        }

        if (directoryEntry.isFile() && absolutePath.endsWith(".jsonl")) {
            sessionFiles.push(absolutePath);
        }
    }

    return sessionFiles;
}

async function parseSessionFileWithCache(
    sessionPath: string,
    logger: Logger,
    cache: Map<string, CachedSessionMetadata>,
    parsedCache: Map<string, ParsedSessionFile>,
    metrics: SessionIndexerMetrics,
): Promise<SessionIndexEntry | undefined> {
    let fileStats: Awaited<ReturnType<typeof fs.stat>>;

    try {
        fileStats = await fs.stat(sessionPath);
    } catch {
        cache.delete(sessionPath);
        logger.warn("Failed to stat session file");
        return undefined;
    }

    const cached = cache.get(sessionPath);
    if (cached && cached.mtimeMs === fileStats.mtimeMs && cached.size === fileStats.size) {
        return cached.entry;
    }

    const entry = await parseSessionFile(sessionPath, fileStats, logger, parsedCache, metrics);
    cache.set(sessionPath, {
        mtimeMs: fileStats.mtimeMs,
        size: fileStats.size,
        entry,
    });

    return entry;
}

async function parseSessionFreshnessWithCache(
    sessionPath: string,
    logger: Logger,
    cache: Map<string, CachedSessionFreshness>,
    parsedCache: Map<string, ParsedSessionFile>,
    parsedInflight: Map<string, Promise<ParsedSessionFile>>,
    metrics: SessionIndexerMetrics,
): Promise<SessionFreshnessSnapshot> {
    let fileStats: Awaited<ReturnType<typeof fs.stat>>;

    try {
        fileStats = await fs.stat(sessionPath);
    } catch (error: unknown) {
        cache.delete(sessionPath);
        logger.warn("Failed to stat session file for freshness");
        throw new Error("Failed to read session freshness", { cause: error });
    }

    const cached = cache.get(sessionPath);
    if (cached && cached.mtimeMs === fileStats.mtimeMs && cached.size === fileStats.size) {
        return cached.freshness;
    }

    if (cached) {
        const incremental = await tryIncrementalFreshness(sessionPath, fileStats, cached);
        if (incremental) {
            cache.set(sessionPath, incremental);
            return incremental.freshness;
        }
    }

    const parsedFreshness = await parseSessionFreshness(
        sessionPath,
        fileStats,
        logger,
        parsedCache,
        parsedInflight,
        metrics,
    );
    cache.set(sessionPath, {
        mtimeMs: fileStats.mtimeMs,
        size: fileStats.size,
        freshness: parsedFreshness.snapshot,
        tailLines: parsedFreshness.tailLines,
        endedWithNewline: parsedFreshness.endedWithNewline,
    });

    return parsedFreshness.snapshot;
}

async function tryIncrementalFreshness(
    sessionPath: string,
    fileStats: Awaited<ReturnType<typeof fs.stat>>,
    cached: CachedSessionFreshness,
): Promise<CachedSessionFreshness | undefined> {
    const nextSize = Number(fileStats.size);
    const appendedBytes = nextSize - cached.size;
    if (
        !cached.endedWithNewline ||
        appendedBytes <= 0 ||
        appendedBytes > MAX_INCREMENTAL_FRESHNESS_BYTES
    ) {
        return undefined;
    }

    const handle = await fs.open(sessionPath, "r");
    try {
        const buffer = Buffer.alloc(appendedBytes);
        const { bytesRead } = await handle.read(buffer, 0, appendedBytes, cached.size);
        if (bytesRead !== appendedBytes) return undefined;
        const appendedText = buffer.toString("utf8");
        const appendedLines = appendedText.split("\n")
            .map((line) => line.replace(/\r$/, "").trim())
            .filter((line) => line.length > 0);
        const appendedEntries = appendedLines.map(tryParseJson);
        if (appendedEntries.some((entry) => !entry)) return undefined;
        const validEntries = appendedEntries.filter((entry): entry is Record<string, unknown> => !!entry);
        const tailLines = [...cached.tailLines, ...appendedLines].slice(-FRESHNESS_HASH_LINE_WINDOW);

        return {
            mtimeMs: Number(fileStats.mtimeMs),
            size: nextSize,
            freshness: {
                sessionPath,
                cwd: cached.freshness.cwd,
                fingerprint: {
                    mtimeMs: Number(fileStats.mtimeMs),
                    sizeBytes: nextSize,
                    entryCount: cached.freshness.fingerprint.entryCount + validEntries.length,
                    lastEntryId: findLastEntryId(validEntries) ?? cached.freshness.fingerprint.lastEntryId,
                    lastEntriesHash: computeLastEntriesHash(tailLines),
                },
            },
            tailLines,
            endedWithNewline: appendedText.endsWith("\n"),
        };
    } finally {
        await handle.close();
    }
}

async function parseSessionFreshness(
    sessionPath: string,
    fileStats: Awaited<ReturnType<typeof fs.stat>>,
    logger: Logger,
    parsedCache: Map<string, ParsedSessionFile>,
    parsedInflight: Map<string, Promise<ParsedSessionFile>>,
    metrics: SessionIndexerMetrics,
): Promise<ParsedFreshness> {
    const parsed = await getParsedSession(
        sessionPath,
        fileStats,
        logger,
        parsedCache,
        parsedInflight,
        metrics,
    );
    const { lines, entries: parsedEntries } = parsed;
    const header = tryParseJson(lines[0]);
    if (!header || header.type !== "session" || typeof header.cwd !== "string") {
        logger.warn("Invalid session header while computing freshness");
        throw new Error("Invalid session header");
    }

    const entryLines = lines.slice(1);
    const lastEntryId = findLastEntryId(parsedEntries);
    const lastEntriesHash = computeLastEntriesHash(entryLines);

    return {
        snapshot: {
            sessionPath,
            cwd: header.cwd,
            fingerprint: {
                mtimeMs: Number(fileStats.mtimeMs),
                sizeBytes: Number(fileStats.size),
                entryCount: parsedEntries.length,
                lastEntryId,
                lastEntriesHash,
            },
        },
        tailLines: entryLines.slice(-FRESHNESS_HASH_LINE_WINDOW),
        endedWithNewline: parsed.endedWithNewline,
    };
}

async function parseSessionFile(
    sessionPath: string,
    fileStats: Awaited<ReturnType<typeof fs.stat>>,
    logger: Logger,
    parsedCache: Map<string, ParsedSessionFile>,
    metrics: SessionIndexerMetrics,
): Promise<SessionIndexEntry | undefined> {
    const parsed = await getParsedSession(sessionPath, fileStats, logger, parsedCache, undefined, metrics)
        .catch(() => undefined);
    if (!parsed || parsed.lines.length === 0) return undefined;

    const { lines } = parsed;
    const header = tryParseJson(lines[0]);
    if (!header || header.type !== "session" || typeof header.cwd !== "string") {
        logger.warn("Skipping invalid session header");
        return undefined;
    }

    const createdAt = getValidIsoTimestamp(header.timestamp) ?? fileStats.birthtime.toISOString();
    let updatedAtEpoch = getTimestampEpoch(header.timestamp) ?? Number(fileStats.mtimeMs);
    let displayName: string | undefined;
    let firstUserMessagePreview: string | undefined;
    let messageCount = 0;
    let lastModel: string | undefined;

    for (const entry of parsed.entries) {

        const activityEpoch = getSessionActivityEpoch(entry);
        if (activityEpoch !== undefined && activityEpoch > updatedAtEpoch) {
            updatedAtEpoch = activityEpoch;
        }

        if (entry.type === "session_info" && typeof entry.name === "string") {
            displayName = entry.name;
            continue;
        }

        if (entry.type !== "message" || !isRecord(entry.message)) {
            continue;
        }

        messageCount += 1;

        const role = entry.message.role;
        if (!firstUserMessagePreview && role === "user") {
            firstUserMessagePreview = extractUserPreview(entry.message.content);
        }

        if (role === "assistant" && typeof entry.message.model === "string") {
            lastModel = entry.message.model;
        }
    }

    return {
        sessionPath,
        cwd: header.cwd,
        createdAt,
        updatedAt: new Date(updatedAtEpoch).toISOString(),
        displayName,
        firstUserMessagePreview,
        messageCount,
        lastModel,
    };
}

async function parseSessionTreeFile(
    sessionPath: string,
    logger: Logger,
    filter: SessionTreeFilter = "default",
    parsedCache: Map<string, ParsedSessionFile>,
    treeCache: Map<string, SessionTreeSnapshot>,
    parsedInflight: Map<string, Promise<ParsedSessionFile>>,
    metrics: SessionIndexerMetrics,
): Promise<SessionTreeSnapshot> {
    const fileStats = await fs.stat(sessionPath);
    const cacheKey = `${sessionPath}:${fileStats.mtimeMs}:${fileStats.size}:${filter}`;
    const cachedTree = treeCache.get(cacheKey);
    if (cachedTree) {
        metrics.treeCacheHits += 1;
        treeCache.delete(cacheKey);
        treeCache.set(cacheKey, cachedTree);
        return cachedTree;
    }
    metrics.treeCacheMisses += 1;
    const parsed = await getParsedSession(
        sessionPath,
        fileStats,
        logger,
        parsedCache,
        parsedInflight,
        metrics,
    );
    const { lines, entries: parsedEntries } = parsed;
    if (lines.length === 0) throw new Error("Session file is empty");

    const header = tryParseJson(lines[0]);
    if (!header || header.type !== "session") {
        throw new Error("Invalid session header");
    }
    const rawEntries = normalizeTreeEntries(parsedEntries);
    const labelsByTargetId = collectLabelsByTargetId(rawEntries);

    const entries: SessionTreeEntry[] = [];

    for (const entry of rawEntries) {
        const entryId = entry.id as string;
        const parentId = typeof entry.parentId === "string" ? entry.parentId : null;
        const entryType = typeof entry.type === "string" ? entry.type : "unknown";
        const timestamp = getValidIsoTimestamp(entry.timestamp);

        const messageRecord = isRecord(entry.message) ? entry.message : undefined;
        const role = extractTreeRole(entry, messageRecord);
        const preview = extractEntryPreview(entry, messageRecord);
        const label = labelsByTargetId.get(entryId);

        entries.push({
            entryId,
            parentId,
            entryType,
            role,
            timestamp,
            preview,
            label,
            isBookmarked: label !== undefined,
        });
    }

    const currentLeafIdRaw = entries.length > 0 ? entries[entries.length - 1].entryId : undefined;
    const filteredEntries = applyTreeFilter(entries, filter);
    const filteredEntryIds = new Set(filteredEntries.map((entry) => entry.entryId));
    const parentIdsByEntryId = new Map(entries.map((entry) => [entry.entryId, entry.parentId]));
    const rootIds = filteredEntries
        .filter((entry) => entry.parentId === null || !filteredEntryIds.has(entry.parentId))
        .map((entry) => entry.entryId);

    const snapshot = {
        sessionPath,
        rootIds,
        currentLeafId: resolveVisibleLeafId(currentLeafIdRaw, filteredEntryIds, parentIdsByEntryId),
        entries: filteredEntries,
    };
    treeCache.set(cacheKey, snapshot);
    while (treeCache.size > MAX_TREE_CACHE_ENTRIES) {
        const oldest = treeCache.keys().next().value;
        if (!oldest) break;
        treeCache.delete(oldest);
    }
    return snapshot;
}

async function getParsedSession(
    sessionPath: string,
    fileStats: Awaited<ReturnType<typeof fs.stat>>,
    logger: Logger,
    cache: Map<string, ParsedSessionFile>,
    inflight: Map<string, Promise<ParsedSessionFile>> = new Map(),
    metrics?: SessionIndexerMetrics,
): Promise<ParsedSessionFile> {
    const revisionKey = `${sessionPath}:${fileStats.mtimeMs}:${fileStats.size}`;
    const pending = inflight.get(revisionKey);
    if (pending) {
        if (metrics) metrics.inflightParseHits += 1;
        return pending;
    }

    const read = readParsedSession(sessionPath, fileStats, logger, cache, metrics);
    inflight.set(revisionKey, read);
    try {
        return await read;
    } finally {
        inflight.delete(revisionKey);
    }
}

async function readParsedSession(
    sessionPath: string,
    fileStats: Awaited<ReturnType<typeof fs.stat>>,
    logger: Logger,
    cache: Map<string, ParsedSessionFile>,
    metrics?: SessionIndexerMetrics,
): Promise<ParsedSessionFile> {
    const cached = cache.get(sessionPath);
    if (cached && cached.mtimeMs === fileStats.mtimeMs && cached.size === fileStats.size) {
        if (metrics) metrics.parseCacheHits += 1;
        cache.delete(sessionPath);
        cache.set(sessionPath, cached);
        return cached;
    }

    if (metrics) {
        metrics.parseCacheMisses += 1;
        metrics.fullFileReads += 1;
    }
    let streamed: { lines: string[]; endedWithNewline: boolean };
    try {
        streamed = await readJsonlLines(sessionPath);
    } catch (error: unknown) {
        logger.warn("Failed to read session file");
        throw new Error("Failed to read session file", { cause: error });
    }
    const parsed: ParsedSessionFile = {
        mtimeMs: Number(fileStats.mtimeMs),
        size: Number(fileStats.size),
        lines: streamed.lines,
        entries: streamed.lines.slice(1).map(tryParseJson)
            .filter((entry): entry is Record<string, unknown> => !!entry),
        endedWithNewline: streamed.endedWithNewline,
    };
    cache.set(sessionPath, parsed);
    while (cache.size > MAX_PARSED_SESSION_CACHE_FILES ||
        [...cache.values()].reduce((total, item) => total + item.size, 0) > MAX_PARSED_SESSION_CACHE_BYTES) {
        const oldest = cache.keys().next().value;
        if (!oldest) break;
        cache.delete(oldest);
    }
    return parsed;
}

async function readJsonlLines(
    sessionPath: string,
): Promise<{ lines: string[]; endedWithNewline: boolean }> {
    const decoder = new StringDecoder("utf8");
    const lines: string[] = [];
    let buffer = "";

    const appendCompleteLines = (): void => {
        while (true) {
            const newlineIndex = buffer.indexOf("\n");
            if (newlineIndex < 0) return;
            const line = buffer.slice(0, newlineIndex).replace(/\r$/, "").trim();
            buffer = buffer.slice(newlineIndex + 1);
            if (line.length > 0) lines.push(line);
        }
    };

    for await (const chunk of createReadStream(sessionPath)) {
        buffer += decoder.write(chunk as Buffer);
        appendCompleteLines();
    }
    buffer += decoder.end();
    const endedWithNewline = buffer.length === 0;
    const finalLine = buffer.replace(/\r$/, "").trim();
    if (finalLine.length > 0) lines.push(finalLine);
    return { lines, endedWithNewline };
}

function normalizeTreeEntries(entries: Record<string, unknown>[]): Record<string, unknown>[] {
    const normalizedEntries: Record<string, unknown>[] = [];
    const seenIds = new Set<string>();
    let previousEntryId: string | null = null;

    for (let index = 0; index < entries.length; index += 1) {
        const source = entries[index];
        const normalized: Record<string, unknown> = { ...source };

        const existingId = typeof source.id === "string" && source.id.length > 0 ? source.id : undefined;
        const generatedId = `legacy-${index.toString(16).padStart(8, "0")}`;
        const idCandidate = existingId ?? generatedId;
        const entryId = seenIds.has(idCandidate) ? `${idCandidate}-${index}` : idCandidate;
        seenIds.add(entryId);
        normalized.id = entryId;

        const hasExplicitNullParent = source.parentId === null;
        const explicitParentId = typeof source.parentId === "string" ? source.parentId : undefined;
        const inferredParentId = explicitParentId ?? (hasExplicitNullParent ? null : previousEntryId);
        normalized.parentId = inferredParentId ?? null;

        if (source.type === "message" && isRecord(source.message) && source.message.role === "hookMessage") {
            normalized.message = {
                ...source.message,
                role: "custom",
            };
        }

        normalizedEntries.push(normalized);
        previousEntryId = entryId;
    }

    return normalizedEntries;
}

function extractTreeRole(
    entry: Record<string, unknown>,
    messageRecord?: Record<string, unknown>,
): string | undefined {
    if (entry.type === "custom_message") {
        return "custom";
    }

    return typeof messageRecord?.role === "string" ? messageRecord.role : undefined;
}

function resolveVisibleLeafId(
    leafId: string | undefined,
    visibleEntryIds: Set<string>,
    parentIdsByEntryId: Map<string, string | null>,
): string | undefined {
    let current = leafId;
    const visitedIds = new Set<string>();

    while (current && !visitedIds.has(current)) {
        visitedIds.add(current);

        if (visibleEntryIds.has(current)) {
            return current;
        }

        const parentId = parentIdsByEntryId.get(current);
        current = parentId ?? undefined;
    }

    return undefined;
}

function collectLabelsByTargetId(entries: Record<string, unknown>[]): Map<string, string> {
    const labelsByTargetId = new Map<string, string>();

    for (const entry of entries) {
        if (entry.type !== "label") continue;
        if (typeof entry.targetId !== "string") continue;

        if (typeof entry.label === "string") {
            const normalizedLabel = normalizePreview(entry.label);
            if (normalizedLabel) {
                labelsByTargetId.set(entry.targetId, normalizedLabel);
                continue;
            }
        }

        labelsByTargetId.delete(entry.targetId);
    }

    return labelsByTargetId;
}

function applyTreeFilter(
    entries: SessionTreeEntry[],
    filter: SessionTreeFilter,
): SessionTreeEntry[] {
    switch (filter) {
        case "default":
            return entries.filter((entry) => entry.entryType !== "label" && entry.entryType !== "custom");
        case "all":
            return entries;
        case "no-tools":
            return entries.filter((entry) => entry.role !== "toolResult");
        case "user-only":
            return entries.filter((entry) => entry.role === "user");
        case "labeled-only":
            return entries.filter((entry) => entry.isBookmarked || entry.entryType === "label");
    }
}

function extractEntryPreview(
    entry: Record<string, unknown>,
    messageRecord?: Record<string, unknown>,
): string {
    if (entry.type === "session_info" && typeof entry.name === "string") {
        return normalizePreview(entry.name) ?? "session info";
    }

    if (entry.type === "label" && typeof entry.label === "string") {
        return normalizePreview(`[${entry.label}]`) ?? "label";
    }

    if (entry.type === "branch_summary" && typeof entry.summary === "string") {
        return normalizePreview(entry.summary) ?? "branch summary";
    }

    if (entry.type === "compaction" && typeof entry.summary === "string") {
        return normalizePreview(`[compaction] ${entry.summary}`) ?? "compaction";
    }

    if (entry.type === "custom_message") {
        const fromContent = extractUserPreview(entry.content);
        if (fromContent) return fromContent;

        if (typeof entry.customType === "string") {
            return normalizePreview(`[custom:${entry.customType}]`) ?? "custom message";
        }

        return "custom message";
    }

    if (messageRecord) {
        const fromContent = extractUserPreview(messageRecord.content);
        if (fromContent) return fromContent;

        if (typeof messageRecord.content === "string") {
            return normalizePreview(messageRecord.content) ?? "message";
        }

        if (messageRecord.role === "toolResult" && typeof messageRecord.toolName === "string") {
            return normalizePreview(`[tool] ${messageRecord.toolName}`) ?? "tool result";
        }
    }

    if (entry.type === "model_change" && typeof entry.modelId === "string") {
        return normalizePreview(`[model] ${entry.modelId}`) ?? "model change";
    }

    if (entry.type === "thinking_level_change" && typeof entry.thinkingLevel === "string") {
        return normalizePreview(`[thinking] ${entry.thinkingLevel}`) ?? "thinking level change";
    }

    if (entry.type === "custom" && typeof entry.customType === "string") {
        return normalizePreview(`[custom:${entry.customType}]`) ?? "custom";
    }

    return "entry";
}

function extractUserPreview(content: unknown): string | undefined {
    if (typeof content === "string") {
        return normalizePreview(content);
    }

    if (!Array.isArray(content)) return undefined;

    const textParts: string[] = [];

    for (const item of content) {
        if (!isRecord(item)) continue;

        if (item.type === "text" && typeof item.text === "string") {
            textParts.push(item.text);
        }
    }

    if (textParts.length === 0) {
        return undefined;
    }

    return normalizePreview(textParts.join(" "));
}

function normalizePreview(value: string): string | undefined {
    const compact = value.replace(/\s+/g, " ").trim();
    if (!compact) return undefined;

    const maxLength = 140;
    if (compact.length <= maxLength) return compact;

    return `${compact.slice(0, maxLength - 1)}…`;
}

function findLastEntryId(entries: Record<string, unknown>[]): string | undefined {
    for (let index = entries.length - 1; index >= 0; index -= 1) {
        const entry = entries[index];
        if (typeof entry.id === "string" && entry.id.length > 0) {
            return entry.id;
        }
    }

    return undefined;
}

function computeLastEntriesHash(entryLines: string[]): string {
    const tail = entryLines.slice(-FRESHNESS_HASH_LINE_WINDOW);
    return createHash("sha256")
        .update(tail.join("\n"))
        .digest("hex");
}

function tryParseJson(value: string): Record<string, unknown> | undefined {
    let parsed: unknown;

    try {
        parsed = JSON.parse(value);
    } catch {
        return undefined;
    }

    if (!isRecord(parsed)) return undefined;

    return parsed;
}

function getSessionActivityEpoch(entry: Record<string, unknown>): number | undefined {
    if (entry.type !== "message" || !isRecord(entry.message)) {
        return undefined;
    }

    const role = entry.message.role;
    if (role !== "user" && role !== "assistant") {
        return undefined;
    }

    if (typeof entry.message.timestamp === "number" && Number.isFinite(entry.message.timestamp)) {
        return entry.message.timestamp;
    }

    return getTimestampEpoch(entry.timestamp);
}

function getTimestampEpoch(value: unknown): number | undefined {
    if (typeof value !== "string") {
        return undefined;
    }

    const timestamp = Date.parse(value);
    if (Number.isNaN(timestamp)) {
        return undefined;
    }

    return timestamp;
}

function getValidIsoTimestamp(value: unknown): string | undefined {
    const timestamp = getTimestampEpoch(value);
    if (timestamp === undefined) {
        return undefined;
    }

    return new Date(timestamp).toISOString();
}

function compareIsoDesc(a: string, b: string): number {
    if (a === b) return 0;

    return a > b ? -1 : 1;
}

function isErrorWithCode(error: unknown, code: string): boolean {
    return isRecord(error) && error.code === code;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

const FRESHNESS_HASH_LINE_WINDOW = 16;
