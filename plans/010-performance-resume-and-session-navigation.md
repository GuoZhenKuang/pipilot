# Plan 010: Performance overhaul for resume, trees, and session switching

> This is a performance/correctness plan for the existing architecture. Preserve Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd. Do not migrate to the Pi SDK, invent RPC commands, remove cwd/session locks, or run device/emulator commands until the operator explicitly says **debug mode**.
>
> Drift baseline: `26cf787`. Before editing, run:
>
> ```bash
> git diff --stat 26cf787..HEAD -- app/src/main/java/com/ayagmar/pimobile/chat app/src/main/java/com/ayagmar/pimobile/sessions app/src/main/java/com/ayagmar/pimobile/ui/chat core-net core-rpc core-sessions bridge/src bridge/test docs plans
> ```

## Status

- **State**: IN PROGRESS
- **Priority**: P1
- **Effort**: XL
- **Depends on**: 008 implementation; 009 may proceed independently where noted
- **Planned at**: `26cf787`

## User-visible problems

- Resuming a long session takes too long.
- `get_tree` can time out on long sessions.
- Switching sessions blocks for too long.
- After switching, the previous chat remains visible for several seconds before the new session draws.
- The Sessions screen can wait on a full remote session index even when a usable cache exists.

## Confirmed code paths

1. `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt:150-185` holds the controller mutex while ensuring the connection, sending `switch_session`, refreshing the active path, resetting projection, and emitting `sessionChanged`.
2. `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt:959-982` clears/reloads on `sessionChanged`, but a retained Chat destination can display its previous `ChatUiState` until that event is collected and the new load completes.
3. `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt:1681-1740` loads `get_messages` and `get_state` sequentially through the controller, then parses and publishes the timeline.
4. `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt:306-345` uses Pi `get_tree` for the active session and bridge tree indexing for inactive sessions.
5. `bridge/src/session-indexer.ts:78-113` caches metadata and freshness independently, but `getSessionTree` calls `parseSessionTreeFile` without a tree cache.
6. `bridge/src/session-indexer.ts:273-306`, `:320-382`, and `:420+` read, split, trim, and parse complete JSONL files independently for freshness, session listing, and trees.
7. `bridge/src/server.ts:564-605` awaits the complete bridge tree operation before responding; the client request timeout is finite.
8. `app/src/main/java/com/ayagmar/pimobile/sessions/BridgeSessionIndexRemoteDataSource.kt:36-76` creates a fresh WebSocket for each session-list fetch and waits for the complete `bridge_sessions` payload.
9. `core-sessions/src/main/kotlin/com/ayagmar/pimobile/coresessions/SessionIndexRepository.kt:24-91` already has disk cache and stale-while-refresh behavior; preserve it and move first-render work ahead of remote refresh.

## Goals and budgets

Add debug-only or sanitized performance telemetry for:

- bridge connection and handshake;
- cwd setup and lock acquisition;
- session switch response;
- first state response;
- first timeline payload received;
- first new-session frame rendered;
- tree request and tree render;
- session-index fetch and parse.

Do not log tokens, authorization headers, client IDs, private paths, message text, or session contents. Use durations, byte counts, entry counts, cache hit/miss, and sanitized operation names.

Initial non-device regression budgets should be deterministic rather than hardware promises:

- cached session index renders before remote refresh begins;
- switching invalidates old visible content immediately;
- one bootstrap path does not issue duplicate `get_state`/`get_messages` requests;
- repeated unchanged tree/freshness requests hit cache;
- large synthetic fixture parsing is linear and bounded by one parse per file revision.

Device budgets are recorded only after operator-owned debug mode.

## Steps

### 1. Add characterization and timing instrumentation

- Add fake-clock/unit timing tests around `SessionsViewModel.resumeSession`, `ChatViewModel` session transitions, `SessionIndexRepository`, and bridge indexer cache behavior.
- Add a sanitized `PerformanceTrace`/metrics model rather than scattered log strings.
- Add bridge counters for parse cache hits, full file reads, tree parses, session-list parses, and active subprocess request latency.
- Add Android counters for stale-frame duration and bootstrap phases.
- Keep production logging restrained; metrics must not expose secrets or private transcript text.

Verify:

```bash
./gradlew :app:testDebugUnitTest --tests '*Performance*' --tests '*Session*'
(cd bridge && pnpm run check)
```

### 2. Stop stale-chat flashes during switching

- Add a replayable active-session identity to `SessionController`/`RpcSessionController`, or an equivalent generation token that updates before navigation can reveal the retained Chat destination.
- On a new session target, publish a `SESSION_SWITCHING` state and clear/hide the old timeline immediately. The UI should show a deliberate loading shell with the target session title, never the previous transcript.
- Tag every bootstrap request with a generation/session path and ignore late results from an older generation.
- Ensure `sessionChanged` remains available for the existing event-driven reload path; the identity state is a safety net, not a second mutation path.
- Add a Compose/source-level contract test for “old timeline is not rendered after target identity changes” and ViewModel tests for late response suppression.

Verify:

```bash
./gradlew :app:testDebugUnitTest --tests '*ChatViewModel*' --tests '*SessionsViewModel*'
./gradlew :app:compileDebugAndroidTestKotlin
```

### 3. Make bootstrap concurrent and staged

- Add a controller-owned bootstrap operation under one mutex that requests state and the current projected timeline without redundant connection/lock work.
- Do not launch independent public controller calls that deadlock or serialize unnecessarily behind the same mutex.
- Render state/session header as soon as state arrives; render a bounded recent timeline as soon as it is available; load older history behind the existing `Load older messages` affordance.
- Preserve documented active-session reads (`get_entries` projection and `get_tree`) and the exact one-explicit-rebuild rule for unknown/incompatible entries.
- If full session history is unavoidable for a particular Pi 0.80.6 path, measure it and keep the previous UI hidden until the new identity is confirmed.
- Add request-count tests proving one bootstrap does not duplicate `get_state`/`get_messages`.

Verify:

```bash
./gradlew :app:testDebugUnitTest --tests '*RpcSessionControllerTest' --tests '*ChatViewModel*'
```

### 4. Cache and incrementally index bridge session files

- Introduce one per-file parsed snapshot keyed by canonical path + mtime + size, shared by metadata, freshness, and inactive tree projection.
- Add bounded LRU eviction by file count and estimated bytes; never keep unbounded full file strings for every session.
- Parse a file once per revision and derive metadata, freshness, and tree entries from that snapshot.
- Use a bounded worker pool for independent session files instead of the current serial `for`/`await` loop. Preserve deterministic sorting.
- For freshness-only checks, use stat plus bounded tail/header reads where safe; fall back to full parse if the tail/header cannot prove the fingerprint.
- Avoid `split/map/filter` copies of entire files where a streaming line parser can preserve the same behavior.
- Add cache invalidation on active mutation and file revision change; never serve a stale tree after the file revision changes.
- Preserve root/path validation, sanitized fixtures, and all existing inactive-session filters.

Verify:

```bash
(cd bridge && pnpm run check)
```

Add synthetic JSONL fixtures with small, medium, and large entry counts. Assert parse count, cache hit/miss, output equivalence, and bounded cache eviction.

### 5. Make tree navigation resilient on long sessions

- Cache inactive tree snapshots by file revision and filter.
- Cache the last authoritative active Pi tree per session identity/leaf, with an explicit stale marker after invalidation.
- Return cached tree data immediately where the UI can label it `Updating tree…`; refresh in the background and replace it when authoritative data arrives.
- Keep Pi `get_tree` authoritative for the active session. Do not replace it with guessed filesystem topology.
- Increase only the tree-specific request timeout after measuring the operation; do not hide a parse problem with an arbitrary global timeout.
- Add cancellation so a tree request for the previous session cannot overwrite the current session's tree.
- Add tests for timeout, cached response, invalidation replacement, and session switch cancellation.

### 6. Reuse transport and session index cache

- Preserve `SessionIndexRepository` cache-first behavior.
- Avoid opening a new WebSocket for every refresh when an authenticated transport/controller is already available and safe to reuse.
- If reuse would violate ownership or host isolation, keep separate transports but add request coalescing and a minimum refresh interval.
- Never let a stale background index refresh block navigation to a cached session.
- Add tests for concurrent refresh coalescing and cache-first rendering.

### 7. Audit bridge request ordering and process lifecycle

- Characterize rapid sequences of `bridge_set_cwd`, control acquisition, `switch_session`, RPC requests, and disconnect/reconnect.
- Serialize per-client control-plane commands where ordering is required; retain Pi RPC request correlation and per-cwd process isolation.
- Ensure a canceled/late switch cannot publish a stale `sessionChanged` or tree result.
- Measure subprocess startup/restart/backoff and idle eviction; do not remove locks or safety behavior.
- Sanitize client-facing errors so filesystem paths and internal exception details are not returned unnecessarily.

### 8. Optimize first-frame Compose work

- Keep timeline rows keyed by stable turn IDs.
- Avoid recomputing projections, syntax highlighting, image metadata, or full output formatting for unchanged turns.
- Move expensive JSON/diff/highlight work to background computation with bounded result caches.
- Keep loading shells structurally stable so session switch does not trigger layout jumps.
- Add recomposition/performance traces only in debug builds and verify no private transcript content is emitted.

### 9. Device acceptance after operator debug mode

Run only after the operator explicitly says **debug mode**:

- resume 1k/10k-entry sessions;
- switch among three long sessions;
- open/close trees repeatedly;
- navigate branches during streaming;
- disconnect/reconnect during switch;
- verify no previous transcript flash;
- validate keyboard, rotation, TalkBack, large font, tablet/landscape, and long-running streaming.

Record measured phase durations and evidence in the plan. Do not claim device success from unit tests.

## Scope boundaries

In scope: `bridge/src/session-indexer.ts`, `bridge/src/server.ts`, bridge tests, `core-sessions`, `core-net`, `RpcSessionController`, `ChatViewModel`, session/chat UI loading states, performance metrics, docs, and fixtures.

Out of scope: Pi SDK migration, undocumented RPC commands, arbitrary laptop file access, removal of locks, deletion of cursor synchronization, changing the one-rebuild rule, or device execution before debug mode.

## Done criteria

- [ ] No old-session transcript is visible after a target switch begins.
- [ ] Late bootstrap/tree results cannot overwrite the current session.
- [ ] Resume/bootstrap avoids duplicate state/timeline requests and renders staged loading state.
- [ ] Bridge parses each unchanged session revision once for metadata/freshness/tree consumers.
- [ ] Long-session tree requests use cache/cancellation and do not time out in approved synthetic fixtures.
- [ ] Session index refresh is cache-first and coalesced.
- [ ] Bridge ordering/process tests pass and errors are sanitized.
- [ ] First-frame projection/formatting work is bounded and cached.
- [ ] Full non-device gates pass; device acceptance remains operator-owned until debug mode.

## STOP conditions

- Stop if Pi 0.80.6 cannot provide enough information for staged projection without guessing private session-file behavior.
- Stop if active tree authority would be weakened; request a product/protocol decision instead.
- Stop if cache invalidation cannot prove a snapshot revision; prefer a safe rebuild over stale data.
- Stop if a performance shortcut changes cwd/session lock semantics.
- Stop before any emulator, connected test, adb, install, or manual device action without explicit **debug mode**.
