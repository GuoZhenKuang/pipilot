# Plan 017: Add an authenticated multi-workspace runtime dashboard

> Fresh-context executor: read this plan completely before editing. This is monitor-only architecture work. Preserve one isolated Pi process per cwd, one active Android controller, and existing cwd/session locks. Monitoring never grants control.

## Status

- State: TODO
- Priority: P2
- Effort: XL (approximately 4–7 focused engineering weeks)
- Depends on: Plans 012, 013, 015 and 016; Plan 014 must be settled (consume its app-scoped foundation if DONE, proceed without background continuity if BLOCKED)
- Category: bridge architecture / monitoring / multi-workspace UX

## Objective

Monitor live runtime state across workspaces and configured hosts while controlling only one session at a time. Add a bounded, authenticated bridge snapshot/event feed keyed by purpose-built opaque workspace references; consume it with at most one monitor transport per selected/configured host; join it to the existing cache-first session index; and attach through the existing local `SessionKey`/resume/lock pipeline. Do not expose new raw cwd/path/client/lock identifiers or create one socket/process per workspace.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution verify Plans 012, 013, 015 and 016 are DONE and Plan 014 is settled. If Plan 014 is DONE, consume its app-scoped controller/snapshot; if BLOCKED, do not claim background continuity and keep monitor ownership in the foreground app graph. Record `PLAN_BASELINE=$(git rev-parse HEAD)` and inventory predecessor changes:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 017 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  bridge/src bridge/test core-net core-rpc core-sessions \
  app/src/main/java/com/ayagmar/pimobile app/src/test docs plans
```

Re-read all process-manager lifecycle/lock tests, server event routing, Plan 012 identity, Plan 013 all-host index and Plan 014 app-scoped controller before designing the protocol.

## Current state at the authoring baseline

- The bridge owns one process entry per cwd, lock maps and aggregate counts; `getStats()` exposes counts, not per-workspace runtime state.
- Server RPC events are forwarded only to the controlling client. It already observes events centrally and can maintain a separate sanitized monitor projection without forwarding raw payloads.
- Session indexes contain raw cwd/path over authenticated transport. This plan must not add either to monitor payloads.
- `SessionController` owns one active control connection. `BridgeSessionIndexRemoteDataSource` retains host-scoped read transports; Plan 013 may have expanded all-host cache aggregation.
- No opaque workspace identity, monitor sequence/revision or all-host runtime repository exists.

## Decisions and invariants

- Add durable 16-byte unpadded-base64url `workspaceReference` mappings in the injected bridge state store established by Plan 012. Perform an explicit backward-compatible state-version migration that preserves every existing share reference; corruption fails closed rather than resetting links. Workspace mappings store canonical cwd internally, but monitor payloads/logs contain only the opaque reference. Include the same reference in authenticated session-index groups so Android can join runtime and cached metadata without raw cwd in monitor events.
- Monitor payload may contain internal `sessionId` only if required for local `SessionKey` attachment and must never log/render it; prefer joining through workspace reference + indexed current session. External share references are not monitor identifiers.
- Bridge feed is authenticated but control-free. It reports advisory states only. Actual `bridge_set_cwd`, control acquisition and resume remain the sole authority.
- Android uses at most one monitor-only authenticated transport per configured host selected for monitoring, never one per cwd. Set a user-visible/configurable host-monitor limit and deterministic eviction/stop policy; a monitor transport never acquires control or forwards arbitrary RPC.
- Runtime phase derives only from documented/passively observed RPC events and process/lock lifecycle. Unknown phase is `unknown`; do not inject unsolicited Pi RPC commands or fabricate model/tool/session state.
- Protocol uses full snapshot plus monotonic per-host `snapshotRevision` and event `sequence`. Gaps, reconnect or server restart trigger one full snapshot request. Unknown/stale events never mutate active control state.
- Bound full snapshots to 256 workspaces and 256 KiB encoded payload by default. If more exist, use deterministic pagination/cursor rather than truncating silently. Batch/coalesce updates to at most 4 emitted batches/second per host and bound pending state to one latest update per workspace.
- No new Pi process may be created solely for monitoring.

## Scope

**In scope**:

- `bridge/src/server.ts`, `process-manager.ts`, `protocol.ts`, `rpc-forwarder.ts`, session index/state modules and focused new runtime-monitor modules
- `bridge/test/**`
- `core-rpc/**` only if established ownership places typed bridge envelopes there; otherwise keep bridge protocol types in `core-net`
- `core-net/src/main/**` and tests for monitor transports/subscriptions/reconnect
- `core-sessions/**` for workspace-reference join metadata if required
- app graph/session repositories/ViewModels and new dashboard UI under `app/src/main/java/com/ayagmar/pimobile/**`
- deterministic benchmark/fake harnesses, tests and docs
- `README.md`, `docs/architecture.md`, `docs/bridge-protocol.md`, `docs/testing.md`, `docs/perf-baseline.md`, `docs/release.md`
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- Simultaneous controllers, takeover, shared editing, merge/conflict resolution or accounts.
- New Pi RPC commands, Pi SDK migration or process creation for observation.
- Raw cwd/path/client/lock owner fields in monitor payload/UI/logs.
- Cloud aggregation or device performance execution before `debug mode`.

## Steps

### Step 1: Specify identity, authorization, sequencing and limits

Write the protocol contract before implementation:

- workspace reference and session-index join;
- full snapshot request/response with revision and pagination;
- coalesced delta with sequence/revision;
- process/phase/availability/stale timestamps;
- reconnect/gap/server-restart behavior;
- advisory lock availability without owner identity;
- payload/count/rate/queue bounds;
- stable sanitized errors and unsupported-version handling.

Threat-model unauthorized clients, token errors, replay/gaps, crafted oversized state, slow subscribers, disconnect, path/reference leakage and hostile workspace labels. Workspace labels remain app-derived from authenticated index metadata; bridge monitor events do not send cwd basenames.

**Verify**:

```bash
(cd bridge && pnpm run check)
./gradlew :core-rpc:test :core-net:test :core-sessions:test
```

Expected: protocol/codec/redaction tests pass and reject raw path/cwd/client/token fields, gaps and oversize payloads.

### Step 2: Add passive bridge runtime projection

Instrument existing process-manager/server event boundaries without changing forwarding. Maintain bounded per-workspace state from process create/exit, control available/held, observed agent start/settled/error/retry and active session changes. Unknown/unobserved fields stay null/unknown. Persist only workspace reference mapping, not runtime snapshots.

Serve authenticated paged snapshots and coalesced deltas. A slow client receives coalesced latest state or an explicit resync-required marker, never an unbounded queue. Test process crash/restart, no process, lock change, session switch, unknown events, server restart, multiple clients, slow subscriber and unauthorized request. Assert process count never increases from monitor operations.

**Verify**:

```bash
(cd bridge && pnpm run check)
```

Expected: all bridge tests pass repeatedly; monitor requests create zero Pi processes and leak no raw identifiers.

### Step 3: Add bounded per-host Android monitor repository

Implement a repository with one monitor transport per enabled host, explicit host-count limit, foreground/background lifecycle policy and cancellation. Reuse host profiles/Keystore tokens but do not reuse the active control transport in a way that mixes subscriptions or lock context. The repository applies snapshots/deltas by revision/sequence, requests full resync on gaps/reconnect and isolates one host's failure.

Join workspace references against Plan 013 cached session groups. Unknown joins render generic workspace state until authenticated index refresh completes. Keep bounded runtime state in memory; if a recent-state cache is justified, it contains only sanitized snapshot fields and explicit expiry.

**Verify**:

```bash
./gradlew :core-net:test :core-rpc:test :core-sessions:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

Expected: fake tests cover host limit/eviction, one socket per host, no socket per cwd, snapshot pagination, gap/reconnect/resync, partial-host failure, unknown join, cancellation and stale expiry.

### Step 4: Render monitor-only dashboard and explicit attach

Integrate with Plan 013 cockpit: working, waiting, retrying, available, controlled elsewhere, disconnected, unknown and stale. Show app-derived host/workspace/session labels only after authenticated index join; otherwise generic labels. Active controller state remains primary.

Opening a card resolves a local `SessionKey` and delegates to existing resume/control acquisition. Sending/actions remain disabled until attachment succeeds. Lock denial shows Retry/Back and no owner identity/takeover. If another local session is running, use Plan 013's conflict behavior rather than switching silently.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

Expected: UI tests cover zero/one/many hosts/workspaces, unknown join, stale/gap, host error, lock denial, current-run conflict and successful attach.

### Step 5: Prove bounded resource behavior

Create a synthetic, disposable benchmark harness—no real Pi, host files, network or user state. Pin Node/JDK versions, use fresh server/repository per candidate, 3 warmups and 5 measured runs, and record raw aggregate data without paths/content. Test 1, 64, 256 and paged 1,024 synthetic workspaces with 0/1/4/16 subscribers and burst updates.

GO thresholds:

- monitor request creates zero additional Pi processes;
- one Android monitor socket per enabled host and zero per workspace;
- encoded page ≤256 KiB and ≤256 entries;
- pending updates ≤one latest entry/workspace and emitted batches ≤4/second/host;
- no dropped sequence is accepted silently; convergence occurs after one explicit full resync;
- p95 in-process snapshot serialization for 256 entries <50 ms on the pinned local environment;
- bridge heap after GC/settle grows <32 MiB over the no-subscriber synthetic baseline at 256 workspaces/16 subscribers.

If timing/memory is inconclusive because the environment cannot be pinned or GC evidence is unstable, mark the measurement INCONCLUSIVE and do not claim the threshold; structural bounds and all correctness tests remain mandatory. If a threshold fails twice, stop and create measured follow-up work rather than weakening bounds.

**Verify**:

```bash
(cd bridge && pnpm run test:coverage)
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm audit --prod)
git diff --check
git status --short
```

Expected: correctness/structural thresholds pass, measurements are recorded as PASS/FAIL/INCONCLUSIVE, and a `git diff --stat <recorded Plan 017 baseline>..HEAD` over the scope shows only in-scope files.

## Done criteria

- [ ] Protocol tests prove opaque workspace identity, authentication, pagination, bounds, sequence/gap handling and raw-field rejection.
- [ ] Bridge tests prove passive monitoring creates no process, preserves forwarding/locks and bounds slow subscribers.
- [ ] Android tests prove one monitor transport per enabled host, none per workspace, host isolation and deterministic resync.
- [ ] UI tests prove monitor-only behavior until existing attach/control succeeds and no silent switch/takeover.
- [ ] Structural resource thresholds pass; timing/memory are recorded honestly as PASS or INCONCLUSIVE, never fabricated.
- [ ] Complete non-device commands exit 0; status shows only in-scope changes.

## STOP conditions

Stop if:

- Monitoring requires duplicate Pi processes, one socket per cwd, unsolicited Pi RPC, lock weakening or simultaneous writers.
- Workspace/session state cannot be joined without adding raw cwd/path to monitor payloads.
- Gap/replay/slow-subscriber handling cannot remain bounded and deterministic.
- A structural GO threshold fails twice; timing-only INCONCLUSIVE is recorded rather than guessed.
- A verification command fails twice after a reasonable correction.
