# Plan 013: Build a session-first cockpit with pins, search and quick reply

> Fresh-context executor: read this plan completely before editing. Execute only after Plan 012. Local saved state uses `SessionKey(hostProfileId, sessionId)`; external links use Plan 012's opaque `SharedSessionLocator`. Never use `sessionPath`, cwd, title or external authority as local saved identity, and never expose absolute paths in default UI.

## Status

- State: IN PROGRESS (reviewer remediation)
- Execution baseline: `f8cb6b6ac8bf269d59a82a84f108ca8f644ffca7`
- Priority: P1
- Effort: XL (approximately 2–4 focused engineering weeks)
- Depends on: Plan 012
- Category: product / navigation / session UX

## Objective

Turn Sessions into the primary work cockpit: current host/workspace context, active session, pinned/recent/hidden organization, cache-first cross-host search/filtering, privacy-safe metadata and text-only quick reply. Preserve the authenticated controller, resume/lock behavior, index cache, generation guards and cursor synchronization. This is native Material 3 work, not a port of Litter's state architecture or custom visual shell.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution, first verify Plan 012 is `DONE`, the working tree contains no unrelated changes, and record `PLAN_BASELINE=$(git rev-parse HEAD)`. Plan 012 is expected to have changed several in-scope files; treat those as predecessor work, not drift.
- Before editing, run:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 013 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt \
  app/src/main/java/com/ayagmar/pimobile/ui/sessions \
  app/src/main/java/com/ayagmar/pimobile/sessions \
  app/src/main/java/com/ayagmar/pimobile/hosts \
  core-sessions app/src/test core-sessions/src/test docs plans
```

Re-read every predecessor-changed file in this scope. During execution, record the baseline SHA in the plan's status/evidence and compare changes against that literal SHA—not `d47ab00`. STOP if Plan 012's internal/external identity distinction is absent.

## Current state at the authoring baseline

- `PiMobileApp.kt` starts at Sessions after host setup and already has compact drawer/expanded rail behavior.
- `ui/sessions/SessionsScreen.kt` wires selected-host search, cwd selection, refresh, resume and active-session actions to `SessionsViewModel`.
- `SessionsViewModel.kt` contains `SessionsUiState` in the same file and owns host loading, per-host observation, cwd preference, warm connection and resume.
- `SessionIndexRepository` stores independent state per host and exposes `initialize(hostId)`, `observe(hostId, query)` and host-scoped throttled/coalesced refresh. It has no all-host aggregator.
- `BridgeSessionIndexRemoteDataSource` retains at most one authenticated read transport per host. It does not acquire control and can support bounded multi-host index refresh.
- Authoring-baseline search matches full `sessionPath` and cwd, even though those invisible/private fields are poor user-facing search contracts.
- No pin/hidden preference store exists.

## Decisions and invariants

- Saved keys are local `SessionKey(hostProfileId, sessionId)`. Profile IDs survive endpoint edits. Deleting/recreating a profile creates a new local scope; do not silently migrate pins by hostname/title/path.
- Only records with a unique valid session ID are pinnable/hideable. Duplicate/legacy records remain browseable and expose a clear disabled reason.
- Pinning a hidden session unhides it; hiding a pinned session unpins it. Hidden sessions remain recoverable through an explicit Hidden filter, and active sessions cannot disappear without that recovery route.
- The saved store contains keys and presentation preferences only—never token, path, cwd, title, preview or transcript text. Tests inject controlled storage.
- Cross-host search is cache-first. It combines each configured host's repository state, displays per-host freshness/error state, and refreshes at most two hosts concurrently. A global query must not block usable cached results on one unreachable host or acquire any control lock.
- User-visible search fields are display name, sanitized first-message preview, model, host display name and friendly workspace label. Do not match full session path or full cwd invisibly.
- Quick reply is text-only. If another session is actively streaming/retrying, do not switch away; show the current-run conflict and offer Open current session. If the target is the current active running session, require explicit Follow up/Steer choice and use existing RPC methods. For an idle target, authenticated resume/control acquisition must finish before normal `sendPrompt`.
- Cancellation/generation guards must prevent a delayed connection/resume from sending after the sheet is dismissed or a different target is chosen. A send failure never auto-navigates; show the failure and offer **Open Chat**.

## Scope

**In scope**:

- `app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/sessions/**`
- new `app/src/main/java/com/ayagmar/pimobile/ui/home/**` only if it creates clearer ownership than extending Sessions
- `app/src/main/java/com/ayagmar/pimobile/sessions/SessionsViewModel.kt` (including `SessionsUiState`) and focused helpers/stores
- narrowly related `app/src/main/java/com/ayagmar/pimobile/hosts/**` only for stable local-key/display mapping
- `core-sessions/src/main/kotlin/com/ayagmar/pimobile/coresessions/**`
- focused Android/core tests with injected storage/clock/dispatchers
- `README.md`, `docs/architecture.md`, `docs/testing.md`, `docs/release.md`
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- New bridge protocol, multi-workspace live monitoring, system notifications, widgets or voice.
- Pi RPC/lock/timeline synchronization changes.
- Storing transcript previews in the pin/hidden preference store.
- Replacing Navigation Compose or rewriting ChatViewModel.
- Device/emulator/ADB/manual acceptance.

## Steps

### Step 1: Add stable, recoverable saved-session state

Implement a small injected pin/hidden/density store using project storage conventions. Validate and bound decoded data; corruption falls back safely without modifying real user state in tests. Reconcile keys against configured profiles and unique remote session IDs. Define deterministic behavior for host deletion/recreation, endpoint edits, stale/deleted sessions, duplicate IDs and old Plan 012 caches.

Expose pin/hide state through the existing Sessions state owner. Add Hidden filter/recovery, enforce pin/hide mutual exclusion, and retain stale pinned placeholders with Remove/Retry rather than rebinding by metadata.

**Verify**:

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest
```

Expected: controlled-storage tests cover round-trip, corruption, bounds, host lifecycle, endpoint edit, stale/deleted/duplicate IDs and pin/hide transitions; persisted values contain keys/preferences only.

### Step 2: Make metadata useful and privacy-safe

Refactor session presentation to prioritize display name, host label, friendly workspace label, relative update time, model, message count, activity/freshness and sanitized preview. Remove absolute session path and full cwd from normal cards, semantics and search suggestions. If technical paths remain necessary, place them behind an explicit secondary details surface with copy confirmation and never log them.

Use stable local keys for Lazy lists. Active/loading/stale/error indicators must derive from real controller/repository state and must not imply control ownership without lock evidence.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :core-sessions:test :app:lintDebug
```

Expected: rendering/search tests prove no default text/semantics contains an absolute path; list keys remain stable across metadata refresh and session-file move.

### Step 3: Add cache-first cross-host cockpit/search

Build one session-first hierarchy:

- selected host and friendly workspace context;
- active session;
- pinned sessions;
- recent sessions;
- Hidden recovery;
- filters for host, workspace, pinned, hidden, active and freshness;
- query across all configured hosts;
- per-host loading/stale/auth/unreachable/error states.

Initialize all configured host caches without waiting for network, then refresh with bounded concurrency and existing per-host backoff/coalescing. Cancellation on profile changes/ViewModel clear must close observation jobs without clearing repository caches. Search ordering is deterministic: active, pinned, updated time, host label, stable key. One host failure must not replace results from other hosts with a global error.

Keep Hosts reachable. Use one-column compact and bounded/two-column expanded presentation without changing navigation libraries. Do not add a global prompt composer.

**Verify**:

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: fake-clock/repository tests cover cached-first render, two-host concurrency bound, partial failure, profile add/delete, ordering, query/filter/hidden behavior and cancellation.

### Step 4: Add row actions and lock-safe quick reply

Add Open, Pin/Unpin, Hide/Unhide, Plan-012 Share/Copy/Revoke delegation, Fork and applicable existing actions. Keep destructive/active-only actions visibly distinct.

Implement a text-only quick-reply sheet with target identity, draft, delivery mode, cancel, send, busy and error state. Required branches:

1. target idle and no other run: resolve current record, authenticate, resume/acquire control, then `sendPrompt`;
2. target is current active run: explicit Follow up or Steer, honoring retry restrictions and current queue semantics;
3. different session is streaming/retrying: do not switch/send; offer Open current session or Cancel;
4. missing token/host/session, stale cache, lock denial or send failure: keep draft, expose recovery, never auto-takeover or auto-navigate;
5. dismiss/retarget during async work: generation cancellation guarantees no late send;
6. repeated taps/callbacks: exactly one dispatch.

After successful idle-target dispatch, show sent state with optional **Open Chat**; do not navigate automatically unless the user selected an explicit “Send and open” action.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: fake-controller tests prove all six branches, target generation, draft retention, exactly-once dispatch and no switch away from another active run.

### Step 5: Accessibility, restoration and documentation

Audit 48dp targets, labels/state descriptions, focus order, large fonts, keyboard/IME, pane titles, live-region errors and compact/expanded overflow. Save only non-sensitive UI selection/query/draft state through supported state owners; do not persist quick-reply text beyond existing draft policy without explicit documentation.

Document cross-host cache behavior, saved-key lifecycle, hidden recovery, privacy and operator-owned device checks. Mark Plan 013 DONE only after complete gates pass.

**Verify**:

```bash
./gradlew ktlintCheck detekt test :app:lintDebug :app:compileDebugAndroidTestKotlin
git diff --check
git status --short
```

Expected: commands pass; a `git diff --stat <recorded Plan 013 baseline>..HEAD` over the scope shows only in-scope implementation/tests/docs/plan-status files.

## Regression expectations

- Existing session cache/refresh/backoff/transport reuse and resume tests remain green.
- Store tests cover all persistence and recovery edges without host state.
- Repository/ViewModel tests cover cache-first all-host aggregation, bounded refresh, partial errors and deterministic order.
- Quick-reply tests cover active-run conflicts, Follow up/Steer, cancellation and duplicate dispatch.
- Compose/source tests cover empty/loading/stale/hidden/compact/expanded/accessibility states.

## Verification

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
```

Expected: every command exits 0, both APKs assemble, Android-test sources compile, no device command runs and diff check emits no output.

### Prior execution evidence — 2026-07-28 (superseded/incomplete)

The historical command results below remain truthful, but the prior completion claim is superseded by reviewer findings. Remediation remains open for: quick-reply run race; empty/loading and cancellation lifecycle; endpoint/token transport reuse; path/error privacy; and invisible New target. These gaps must be resolved and the affected evidence reverified before this plan can return to DONE.

- Baseline/drift check recorded literal baseline `f8cb6b6ac8bf269d59a82a84f108ca8f644ffca7`; Plan 012 identity split was present and its status was DONE.
- Host-scoped `selectedCwdByHost`, selected-host groups, profile lifecycle, all-host filter and cross-host resume regressions are covered by `SessionsViewModelTest`.
- Saved-key, privacy/search, deterministic ordering and composed cockpit filter/freshness behavior are covered by unit tests; `SessionsCockpitScreenTest` covers no-host, hidden empty, filters, stale status and path-free default card semantics.
- Quick-reply tests cover idle resume/send, active Follow up, successful Steer, competing streaming/retry/null-key conflicts, stale/missing targets, missing token, lock denial, send failure, cancellation/retarget, duplicate taps and concurrent switch guards. Controller-level expected-key guards are also tested.
- `./gradlew :core-sessions:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`: PASS.
- `./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease`: PASS; debug and unsigned release APKs produced.
- `./gradlew :app:compileDebugAndroidTestKotlin`: PASS.
- `(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)`: PASS; 87 tests passed and no known production vulnerability was reported.
- `git diff --check`: PASS.
- Device/emulator/ADB/connected/manual acceptance: not run; PENDING — operator-owned.

## Done criteria

The prior checked completion claims are superseded and incomplete pending reviewer remediation and fresh verification.

- [ ] Store tests prove pins/hidden use local profile ID + unique `sessionId`, persist no path/cwd/transcript, and always provide Hidden recovery.
- [ ] UI/search tests prove normal cards/semantics/query results expose no absolute path/full cwd.
- [ ] Multi-host tests prove cache-first results, bounded refresh, deterministic ordering and partial-host failure isolation.
- [ ] Quick-reply tests prove all active/idle/cancel/error branches, exactly-once dispatch and no automatic lock takeover/navigation.
- [ ] Compact/expanded/accessibility source tests compile and lint passes.
- [ ] Complete non-device commands above exit 0; status shows only in-scope changes.

## STOP conditions

Stop if:

- Plan 012's internal `SessionKey`/external share-locator split is absent.
- Saved state would need path/title/authority rebinding or transcript persistence.
- All-host search would acquire control, block cached results on network, or create unbounded concurrent transports.
- Quick reply would bypass `SessionController`, switch away from another active run, or send after cancellation.
- The cockpit requires an unrelated ChatViewModel rewrite or protocol change.
- A verification command fails twice after a reasonable correction.
