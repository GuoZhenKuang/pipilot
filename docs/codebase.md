# Pi Mobile Codebase Guide

This document explains how the Pi Mobile project is organized, how data flows through the system, and where to make changes safely.

For visual system diagrams, see [Architecture (Mermaid diagrams)](architecture.md).
For durable decision rationale, see [Architecture Decision Records](adr/README.md).

## Table of Contents

- [System Overview](#system-overview)
- [Repository Layout](#repository-layout)
- [Module Responsibilities](#module-responsibilities)
- [Key Runtime Flows](#key-runtime-flows)
  - [1) Connect and Resume Session](#1-connect-and-resume-session)
  - [2) Prompt and Streaming Events](#2-prompt-and-streaming-events)
  - [3) Reconnect and Resync](#3-reconnect-and-resync)
  - [4) Session Trees and Navigation](#4-session-trees-and-navigation)
  - [5) Session Coherency Monitoring + Sync](#5-session-coherency-monitoring--sync)
- [Bridge Control Model](#bridge-control-model)
- [State Management in Android](#state-management-in-android)
- [Testing Strategy](#testing-strategy)
- [Common Change Scenarios](#common-change-scenarios)
- [Reference Files](#reference-files)

## System Overview

```text
Android App (Compose)
    │ WebSocket (envelope: { channel, payload })
    ▼
Bridge (Node.js)
    │ stdin/stdout JSON RPC
    ▼
pi --mode rpc
    + internal extensions (pi-mobile-tree, pi-mobile-open-stats)
```

The app never talks directly to a pi process. It talks to the bridge, which:

- handles auth and client identity
- manages one pi subprocess per cwd
- enforces single-client control lock per cwd/session
- forwards RPC events and bridge control messages

This retained boundary is documented in [ADR-0004](adr/ADR-0004-retain-rpc-subprocess-boundary.md): one isolated `pi --mode rpc` process per cwd.

## Repository Layout

| Path | Purpose |
|---|---|
| `app/` | Android UI, view models, host/session UX |
| `core-rpc/` | Kotlin RPC command/event models and parser |
| `core-net/` | WebSocket transport, envelope routing, reconnect/resync |
| `core-sessions/` | Session index models, cache, repository logic |
| `bridge/` | Node bridge server, protocol, process manager, extensions |
| `benchmark/` | Android macrobenchmark module and baseline-profile scaffolding |
| `docs/` | Human-facing project docs |
| `docs/ai/` | Planning/progress artifacts |

## Module Responsibilities

### `app/` (Android application)

- Compose screens and overlays
- `ChatViewModel`: chat timeline, command palette, extension dialogs/widgets, tree/stats/model sheets
- `RpcSessionController`: high-level session operations backed by `PiRpcConnection`
- Host management and token storage

### `core-rpc/`

- `RpcCommand` sealed models for outgoing commands
- `RpcIncomingMessage` sealed models for incoming events/responses
- `RpcMessageParser` mapping wire `type` → typed event classes

### `core-net/`

- `WebSocketTransport`: reconnecting socket transport with outbound queue
- `PiRpcConnection`:
  - wraps socket messages in envelope protocol
  - routes bridge vs rpc channels
  - performs handshake (`bridge_hello`, cwd set, control acquire)
  - exposes `rpcEvents`, `bridgeEvents`, and `resyncEvents`

### `core-sessions/`

- Host-scoped session index state and filtering
- cache-first rendering with background refresh
- per-host refresh coalescing, throttling, and failure backoff
- in-memory and file cache implementations

### `bridge/`

- `server.ts`: WebSocket server, token validation, protocol dispatch, health endpoint
- `process-manager.ts`: per-cwd forwarders + control locks
- `rpc-forwarder.ts`: pi subprocess lifecycle/restart/backoff
- `session-indexer.ts`: streaming JSONL indexing with shared revision snapshots, bounded tail freshness, tree caches, and bounded eviction
- `extensions/`: internal mobile bridge extensions

## Key Runtime Flows

### 1) Connect and Resume Session

1. App creates `PiRpcConnectionConfig` (`url`, `token`, `cwd`, `clientId`).
2. Bridge returns `bridge_hello`; the app sets cwd and acquires control.
3. Before a retained Chat destination can render, `RpcSessionController` publishes a new active-session generation with `isSwitching = true`, so the previous transcript is hidden immediately.
4. For a selected session, the controller sends documented `switch_session`, refreshes the authoritative active path, resets its entry projection, and publishes the settled generation.
5. Controller-owned bootstrap stages `get_state` first, then uses the current `get_entries` projection. It falls back to documented `get_messages` only when no safe projection is available.
6. `ChatViewModel` tags bootstrap and tree work with the generation and rejects late results after another switch.

### 2) Prompt and Streaming Events

1. User sends prompt from `ChatViewModel`
2. `RpcSessionController.sendPrompt()` sends `prompt`
3. Bridge forwards RPC payload to active cwd process
4. pi emits streaming events (`message_update`, tool events, `agent_end`, etc.)
5. `ChatViewModel` updates timeline and streaming state

### 3) Reconnect and Resync

`WebSocketTransport` auto-reconnects with exponential backoff.

On reconnect, `PiRpcConnection`:

- waits for new `bridge_hello`
- re-acquires cwd/control if needed
- emits `RpcResyncSnapshot` after `get_state + get_entries`, using the last entry ID as a reconnect cursor

This keeps timeline and streaming flags consistent after network interruptions.

### 4) Session Trees and Navigation

Tree display and navigation have separate authority:

- The active session uses Pi's documented `get_tree`. The app may show its last cached result as stale while an authoritative refresh runs in the background.
- Inactive-session browsing uses the bridge's validated, revision-keyed JSONL tree cache.
- Session switches cancel or generation-reject late tree results.

Navigation remains the one internal-extension path because Pi 0.80.6 has no tree-navigation RPC command:

1. App sends `bridge_navigate_tree { entryId }`.
2. Bridge validates cwd/control and the requested entry ID.
3. Bridge sends RPC `prompt` with `/pi-mobile-tree <entryId> <statusKey>`.
4. Extension emits `setStatus(statusKey, JSON payload)`.
5. Bridge returns a sanitized `bridge_tree_navigation_result`.
6. App updates the editor draft and refreshes the authoritative tree.

### 5) Session Coherency Monitoring + Sync

Bridge-observed mutations push `bridge_session_invalidated` for immediate cursor resync. To cover direct terminal or other external edits, `ChatViewModel` also performs one safety freshness check every 60 seconds only while Chat is foreground-active.

The bridge computes a sanitized fingerprint from file revision and bounded parse/tail data. The app classifies a changed fingerprint as follows:

- inside the local mutation grace window: update the baseline;
- no explicit other-client owner while idle: refresh silently;
- no explicit other-client owner while busy: defer refresh until idle;
- explicit other-client lock ownership: show one throttled, actionable conflict with **Sync now**;
- reload failure: show an actionable recovery error.

Unknown cursors, branch moves, session replacement, unsupported entries, or invalid projections trigger exactly one explicit full rebuild. The app never treats a fingerprint change alone as proof of a conflict.

## Bridge Control Model

The bridge uses lock ownership to prevent conflicting writers.

- Lock scope: cwd (and optional sessionPath)
- Only lock owner can send RPC traffic for that cwd
- Non-owner receives `bridge_error` (`control_lock_required` or `control_lock_denied`)

This protects session integrity when multiple mobile clients are connected.

## State Management in Android

Primary state owner: `ChatViewModel` (`StateFlow<ChatUiState>`).

Important sub-states:

- connection + streaming state
- timeline (windowed history + realtime updates)
- command palette and slash command metadata
- extension dialogs/notifications/widgets/title
- bash dialog state
- stats/model/tree bottom-sheet state
- deferred freshness refresh, explicit-owner conflict, and sync-in-progress state

High-level design:

- transport/network concerns stay in `core-net` + `RpcSessionController`
- rendering concerns stay in Compose screens
- event-to-state logic stays in `ChatViewModel`

## Testing Strategy

### Android

- ViewModel-focused unit tests in `app/src/test/...`
- Covers command filtering, extension workflow handling, timeline behavior, queue semantics

### Bridge

- Vitest suites under `bridge/test/...`
- Covers auth, malformed payloads, control locks, reconnect, tree navigation, health endpoint

### Commands

```bash
# Complete Android non-device gate
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin

# Bridge quality and production dependency gate
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

## Common Change Scenarios

### Add a new RPC command end-to-end

1. Add command model in `core-rpc/RpcCommand.kt`
2. Add encoder mapping in `core-net/RpcCommandEncoding.kt`
3. Add controller method in `RpcSessionController`
4. Call from ViewModel/UI
5. Add tests in app + bridge (if bridge control involved)

### Add a new bridge control message

1. Add message handling in `bridge/src/server.ts`
2. Add payload parser/use site in Android (`PiRpcConnection.requestBridge` caller)
3. Add protocol docs in `docs/bridge-protocol.md`
4. Add tests in `bridge/test/server.test.ts`

### Add a new internal extension workflow

Follow `docs/extensions.md` checklist.

## Reference Files

- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/PiRpcConnection.kt`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/WebSocketTransport.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/RpcCommand.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/RpcIncomingMessage.kt`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `bridge/src/session-indexer.ts`
