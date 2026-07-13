# Plan 005: Decompose chat state and UI without changing behavior

> **Executor instructions**: This is a behavior-preserving refactor. Add characterization tests first, move one responsibility at a time, and keep each commit green. Update `plans/README.md` when done.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- app/src/main/java/com/ayagmar/pimobile/chat app/src/main/java/com/ayagmar/pimobile/sessions app/src/main/java/com/ayagmar/pimobile/ui/chat app/src/test plans`

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/003-current-pi-rpc-conformance.md`
- **Category**: tech-debt
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

`ChatScreen.kt` is about 3,900 lines, `ChatViewModel.kt` 3,252, and `RpcSessionController.kt` 1,459. A broad UX redesign on top of these files would increase coupling and regression risk. The goal is not a framework rewrite: create readable, feature-owned files while preserving public interfaces and behavior.

## Current state

- `ChatViewModel` owns timeline, streaming, commands, dialogs, tree, stats, models, bash, images, freshness, and diagnostics.
- `ChatScreen.kt` contains route wiring, headers, timeline rendering, composer, tool/thinking cards, sheets, dialogs, formatting, and constants.
- `RpcSessionController` mixes connection lifecycle, command requests, parsing, and recovery.
- Existing tests are strongest around ViewModel thinking/workflows and controller parsing; preserve them.
- Project style favors `StateFlow`, early returns, explicit models, small Compose components, and no DI framework.

## Commands

```bash
./gradlew :app:testDebugUnitTest :core-net:test :core-rpc:test
./gradlew ktlintCheck detekt test :app:lintDebug
```

## Scope

**In scope**: the three oversized files, new feature-owned files in their existing packages, close tests, and plan status.

**Out of scope**: new user-visible behavior, navigation redesign, dependency injection frameworks, new state-management libraries, protocol changes, broad design-system work.

## Steps

### Step 1: Freeze behavior

Create a behavior inventory and add missing characterization tests for public ViewModel/controller actions and major UI state transitions: initial/empty, streaming, tool execution, retry, compaction, queue, extension dialogs, tree/model/stats/bash sheets, images, reconnect, and errors. Prefer state assertions over snapshots.

**Verify**: all tests pass before production moves begin.

### Step 2: Extract pure parsing and presentation logic

Move JSON response mapping and stat/model/tree parsing out of `RpcSessionController` into package-local mapper files with focused tests. Move pure Chat formatting/presentation functions out of Compose screen. Do not add generic “utils” files; name files by domain.

**Verify**: controller and UI tests pass; moved functions have direct tests.

### Step 3: Split controller collaborators by existing responsibility

Extract connection lifecycle/reconnect ownership and typed RPC request operations behind small package-local classes. Keep `SessionController` as the app-facing boundary. Do not introduce interfaces with only speculative reuse; interfaces are allowed only where existing tests already fake the boundary.

**Verify**: public `SessionController` behavior and fake test utility remain compatible.

### Step 4: Split ViewModel reducers/coordinators

Keep one screen ViewModel but delegate pure state transitions to feature-specific reducers already suggested by data ownership: timeline/run state, extension UI, and auxiliary sheets. Avoid nested ViewModels and avoid widening nullability. Coroutines and side effects remain visible in the top-level coordinator or a narrowly named existing-service boundary.

**Verify**: all characterization tests pass after each extraction.

### Step 5: Split Compose by visible region

Move route, top bar/status, timeline items, composer/run controls, and each overlay family into separate files. Keep state hoisted and callbacks explicit. Reuse `PiButton`, `PiCard`, `PiTextField`, `PiTopBar`, and theme tokens. Do not change dimensions/copy/layout in this plan.

**Verify**: `:app:lintDebug`, unit tests, and existing androidTest compilation pass.

### Step 6: Remove stale suppressions and dead code

Remove suppressions no longer needed, unused constants/imports/parameters, and stale comments. Do not silence newly surfaced complexity warnings globally.

**Verify**: `rg -n '@Suppress\("(LongMethod|TooManyFunctions)'` shows a material reduction in the three original files; full quality gate passes.

## Test plan

Match existing test styles. No screenshot baselines are required, but existing Compose tests must compile. Perform manual smoke against a fake or real bridge for prompt, stream, abort, steer, follow-up, tool expansion, extension dialog, tree, model, stats, bash, image, and reconnect.

## Done criteria

- [ ] Behavior inventory is covered by tests.
- [ ] No original file remains a multi-responsibility dumping ground; target under roughly 1,000 lines per file unless a reviewer-approved reason is recorded.
- [ ] No new framework/dependency was added.
- [ ] Public behavior and protocol are unchanged.
- [ ] Full gates and manual smoke pass.
- [ ] Plan row is DONE.

## STOP conditions

- A move requires a public protocol or UX change.
- Proposed extraction creates circular dependencies.
- Tests cannot distinguish existing behavior from accidental behavior; report the ambiguity before choosing.

## Execution note

The UI and pure mapping targets are met: visible regions, history parsing, RPC parsing, models, tree mapping, and formatting now live in feature-owned files around or below 1,000 lines. `ChatViewModel` remains about 2,800 lines and `RpcSessionController` about 1,250 lines because coroutine ordering and the public `SessionController` boundary remain centralized to preserve reconnect, streaming, and extension-event behavior. Further mechanical splitting would distribute side effects without establishing clearer ownership; reducer and mapper logic was extracted instead. This is the concrete exception to the approximate per-file target.

## Maintenance notes

Review for “file splitting without responsibility splitting.” The result should make a feature change local, not merely distribute the same global state across many files.
