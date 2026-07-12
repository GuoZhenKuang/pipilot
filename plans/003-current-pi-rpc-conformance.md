# Plan 003: Bring Pi Mobile up to the current Pi RPC contract

> **Executor instructions**: Treat the installed Pi `docs/rpc.md` as authoritative. Add characterization tests before changing behavior. Update `plans/README.md` when complete.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- core-rpc core-net app/src bridge/test docs plans`

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/002-secure-and-pin-the-bridge.md`
- **Category**: migration
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

Pi added lifecycle and session commands after this client was written. The app currently treats `agent_end` as final even when retry, compaction, or queued continuation may follow; it also omits current command/field variants. A conformance fixture is needed so future Pi upgrades fail in tests rather than at runtime.

## Current state

- Current local Pi docs: `.../@earendil-works/pi-coding-agent/docs/rpc.md`.
- Current documented additions include `agent_settled`, `get_entries`, `get_tree`, `clone`, `contextUsage`, and model-dependent `max` thinking.
- `core-rpc/.../RpcIncomingMessage.kt:122` models `AgentEndEvent` but no settled event.
- `ChatViewModel.kt:975` clears running state on `AgentEndEvent`.
- `RpcSessionController.kt:980` reacts to agent end/error for controller state.
- `ChatScreen.kt:2811` hardcodes thinking through `xhigh`.
- `RpcSessionController.kt:1354` reads legacy `context`; current stats use `contextUsage`.
- Parser patterns: `RpcMessageParserTest.kt`; controller mapping patterns: `RpcSessionControllerTest.kt`.

## Commands

```bash
./gradlew :core-rpc:test :core-net:test :app:testDebugUnitTest
(cd bridge && pnpm run check)
./gradlew ktlintCheck detekt test
```

All must exit 0. If Gradle cannot download dependencies, retry only after network is restored; do not mark done with Android checks skipped.

## Scope

**In scope**: RPC models/parser/tests in `core-rpc`; connection/controller/ViewModel/UI mappings and tests in `core-net` and `app`; bridge protocol conformance tests; compatibility docs; plan status.

**Out of scope**: consuming `get_tree/get_entries` beyond typed support (plan 004), visual redesign, SDK embedding, removing legacy field fallbacks.

## Steps

### Step 1: Capture the authoritative protocol surface

Read current `rpc.md` completely. Create a checked-in, sanitized conformance fixture set under `core-rpc/src/test/resources/rpc/` covering every command/event the app consumes, including unknown fields. Add a short compatibility document naming the Pi version used to generate/verify fixtures. No session contents or credentials.

**Verify**: fixture parser test enumerates all fixture files and parses each successfully.

### Step 2: Model new lifecycle and command types

Add typed support for `agent_settled`, `get_entries`, `get_tree`, and `clone`, preserving `GenericRpcEvent` handling for future unknown events. Model only fields documented by Pi and needed by the client. Add command encoding and parser tests.

**Verify**: focused core tests pass and `rg 'agent_settled|get_entries|get_tree|clone' core-rpc core-net` finds models plus tests.

### Step 3: Correct lifecycle semantics

Keep `agent_end` useful for per-run diagnostics but do not mark the overall session idle solely from it when Pi may continue automatically. Use `agent_settled` as the definitive settled boundary. Preserve compatibility with older supported Pi only if the compatibility policy explicitly includes versions without `agent_settled`; implement that fallback at one documented boundary, not in multiple ViewModels.

Test: plain completion, retry after `agent_end`, overflow compaction retry, queued follow-up, abort, reconnect/resync during activity.

**Verify**: ViewModel/controller tests prove controls remain “running” between `agent_end` and `agent_settled`.

### Step 4: Align stats, thinking, and state fields

Parse canonical `contextUsage.tokens/contextWindow/percent`, including documented nulls immediately after compaction, while retaining tested legacy fallbacks. Derive available thinking levels from model/RPC capability where possible; if RPC does not expose an explicit list, include `max` and handle a rejected set command visibly without corrupting local state.

**Verify**: tests cover canonical, null-after-compaction, and legacy stats; thinking tests cover rejection rollback.

### Step 5: Add bridge pass-through conformance

Add bridge tests proving command IDs, unknown fields, events without IDs, asynchronous interleaving, `agent_settled`, and new commands pass through unchanged. The bridge must not reimplement or normalize Pi RPC payloads.

**Verify**: `cd bridge && pnpm run check` passes.

### Step 6: Update compatibility documentation

Document supported/tested Pi version, upgrade procedure, fixture refresh procedure, and current RPC capabilities. Do not claim plan 004 behavior yet.

## Test plan

Tests are required before implementation for each corrected behavior. Follow existing parser/controller tests. Add at least one cross-layer test that sends fixture envelopes through `PiRpcConnection` and verifies typed events and correlation.

## Done criteria

- [ ] Current RPC fixtures are checked in and sanitized.
- [ ] New commands/events parse and encode.
- [ ] `agent_settled` controls final run state.
- [ ] Canonical `contextUsage` and `max` are handled.
- [ ] Bridge remains transparent and conformance tests pass.
- [ ] Full Android and bridge gates pass.
- [ ] Plan row is DONE.

## STOP conditions

- Installed Pi docs differ materially from the features listed above.
- Supporting old and current Pi requires ambiguous lifecycle heuristics; stop and request a minimum-version decision.
- A new model would require widening nullability outside documented protocol fields.

## Maintenance notes

Protocol fixtures are compatibility tests, not snapshots of private sessions. Reviewers should scrutinize lifecycle ordering and ensure the bridge remains a pass-through.
