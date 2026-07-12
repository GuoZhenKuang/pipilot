# Plan 004: Simplify tree handling and implement incremental synchronization

> **Executor instructions**: Preserve control-lock and reconnect guarantees from ADR-0002/0003. Introduce new paths before deleting old ones. Update the plan index when done.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- bridge/src bridge/test core-rpc core-net app/src docs plans`

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/003-current-pi-rpc-conformance.md`
- **Category**: perf
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

Reconnect currently downloads all messages and active chat polls freshness every four seconds. Tree navigation tunnels a private slash command through extension status events even though current Pi exposes authoritative tree/entry reads. This plan adopts current read APIs and cursor synchronization while retaining the smallest bridge-specific navigation mechanism Pi still requires.

## Current state

- `PiRpcConnection.buildResyncSnapshot()` requests `get_state` then `get_messages`.
- `ChatViewModel` polls freshness every 4,000 ms and caps history locally.
- Bridge session indexer manually parses tree files.
- `pi-mobile-tree.ts` plus `server.ts` status waiters perform direct navigation because current RPC documentation still lacks a direct navigation command.
- ADR-0003 requires deterministic reconnect resync and drift protection; preserve the outcome, not necessarily polling.

## Commands

Run focused tests after each step, then:

```bash
./gradlew ktlintCheck detekt test
(cd bridge && pnpm run check)
```

## Scope

**In scope**: tree/entry RPC models added in plan 003; connection/controller/chat synchronization; bridge invalidation and remaining navigation endpoint; session indexer only where duplication can be safely removed; tests/docs; plan status.

**Out of scope**: SDK migration, changing lock ownership, pagination UI, broad Chat UI decomposition, session-list removal.

## Steps

### Step 1: Characterize reconnect and tree behavior

Add tests for initial full load, cursor catch-up, unknown cursor, active branch movement, reconnect during streaming, external session mutation, navigation changing leaf without appending, and control-lock denial. These tests must fail if messages are duplicated or lost.

**Verify**: focused tests pass against current behavior except explicitly marked new cursor cases, which should fail before implementation.

### Step 2: Use Pi `get_tree` for authoritative topology

Route tree reads through the active Pi RPC runtime when a process exists and the caller owns control. Preserve bridge-owned read-only filesystem fallback only for inactive sessions/session browsing where no runtime exists. Keep the external Android tree model stable initially. Remove duplicate filtering only after proving each filter maps exactly; otherwise retain bridge presentation filtering over Pi entries.

**Verify**: integration tests compare active-runtime tree responses with fixture topology, labels, and current leaf.

### Step 3: Implement entry-cursor synchronization

Store the last applied entry ID per active session. On resync, call `get_entries { since }`; append only returned entries and reconcile against returned `leafId`. If Pi rejects an unknown cursor or branch movement invalidates the local projection, perform one explicit full rebuild and reset the cursor. Initial session open may still perform a full fetch.

Keep all cursor/rebuild decisions in the session/controller layer, not Compose.

**Verify**: tests prove no full `get_messages` call on ordinary reconnect and exactly one fallback rebuild on invalid cursor.

### Step 4: Replace periodic polling with invalidation plus safety fallback

Have the bridge emit a bridge-channel session-invalidated event for mutations it observes from its Pi process and bridge imports/navigation. Android schedules cursor resync on that event. Retain low-frequency foreground safety polling only for external terminal edits the bridge cannot observe; choose/document a materially longer interval and pause it when app/chat is not active.

**Verify**: fake-clock tests show no four-second continuous polling, immediate local invalidation resync, and eventual external-edit detection.

### Step 5: Minimize private tree navigation plumbing

Confirm from current Pi RPC docs that no direct navigation command exists. If absent, retain one bridge navigation message and the internal extension, but remove `get_commands` probing and user-command coupling where a deterministic internal load contract suffices. If a direct RPC navigation command now exists, replace the extension path and delete it with its tests/docs. Do not invent an undocumented command.

**Verify**: navigation, cancellation, editor draft, leaf override, prompt-after-navigation, fork, reconnect, and failure tests pass.

### Step 6: Measure and document

Add deterministic counters or test instrumentation for full-history fetches, incremental entries, and freshness polls. Update performance docs with a reproducible large-session scenario and before/after payload counts; do not fabricate device timings.

## Test plan

Use existing `PiRpcConnectionTest`, `RpcSessionControllerTest`, Chat ViewModel tests, and bridge server/indexer tests. Add fake runtime fixtures rather than requiring a live model. Manual smoke: open old session, prompt, reconnect, edit same session in terminal, navigate branch, fork, and confirm exact timeline.

## Done criteria

- [ ] Active tree uses authoritative Pi topology.
- [ ] Ordinary reconnect uses entry cursors, not full history.
- [ ] Invalid cursors safely rebuild once.
- [ ] Four-second polling is removed.
- [ ] External edits remain eventually detectable.
- [ ] Private navigation mechanism is reduced to the actual remaining RPC gap.
- [ ] Full gates and manual smoke pass.
- [ ] Plan row is DONE.

## STOP conditions

- `get_entries` does not provide stable IDs/leaf semantics described by current Pi docs.
- Incremental entries cannot reconstruct the app timeline without private session-schema assumptions.
- Removing a bridge tree path would prevent browsing inactive sessions.

## Maintenance notes

A cursor is valid only relative to its session file. Review branch changes, compaction, import, and session replacement carefully; these are the paths most likely to require a full rebuild.
