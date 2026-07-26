# Plan 014: Add privacy-preserving background turn continuity and an active-session widget

> Fresh-context executor: read this plan completely before editing. Preserve the authenticated WebSocket bridge, one process per cwd, lock semantics and reconnect/resync rules. Do not promise guaranteed completion delivery after force-stop/OS process death without a server push channel, and do not introduce a cloud relay in this plan.

## Status

- State: TODO
- Priority: P1
- Effort: XL (approximately 3–4 focused engineering weeks)
- Depends on: Plans 012 and 013
- Category: Android lifecycle / notifications / continuity

## Objective

Provide opt-in, best-effort local continuity while Android permits the app's foreground service to remain alive: retain the one app-scoped authenticated controller during an active turn, show a privacy-safe ongoing notification, and notify completion/failure when observed. Notification/widget taps reopen the exact local `SessionKey` through Plan 012's common resolver. Add a minimal current-active-session widget after the same sanitized snapshot is reliable. Explicitly handle platform time limits, permission denial, process recreation and the unavoidable gap where no local process is alive to observe bridge events.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution verify Plans 012–013 are DONE, record `PLAN_BASELINE=$(git rev-parse HEAD)`, inventory predecessor changes, and compare later work to that baseline:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 014 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  app/src/main/AndroidManifest.xml app/src/main/java/com/ayagmar/pimobile \
  app/src/main/res app/build.gradle.kts app/src/test app/src/androidTest docs plans
```

Re-read Plan 012 routing and Plan 013 active-session state before editing. STOP if they do not expose a single stable local target/resume path.

## Current state at the authoring baseline

- `SessionController` exposes `connectionState`, `isStreaming`, `activeSession`, `rpcEvents` and reconnect state.
- `RpcSessionController` itself updates `isStreaming` from prompt dispatch, `agent_start`, `agent_settled` and resync; continuity does not need ChatViewModel to infer run state.
- `AppGraph` is constructed lazily inside `MainActivity`. A service constructing another graph would create a second controller/transport.
- `MainActivity.onDestroy()` disconnects when finishing, which would terminate observation even if background continuity were active.
- The manifest has no custom `Application`, service, notification permission/channel or widget receiver.
- Plan 012 provides local `SessionKey` and external `SharedSessionLocator`; system notifications/widgets must use explicit immutable intents with the local key, not exported share URLs or lazily created public share references.

## Decisions and platform constraints

- Add one application-scoped graph/controller in the main process. Activity and service obtain the same instance; the service must not run in a separate Android process or create another WebSocket.
- Before implementation, verify API 37 foreground-service types, start restrictions, notification permission behavior and duration quotas from official Android documentation. Record the selected lawful service type and limits in `docs/dependency-matrix.md` or an ADR.
- Decision gate outcomes:
  - **GO**: a documented service type supports the intended active-turn duration; implement within its limits.
  - **LIMITED**: only time-limited service is lawful; implement with an explicit countdown/expiry state, stop before quota violation, and document that later completion will be learned on next authenticated resync.
  - **NO-GO**: no lawful type fits; mark this plan BLOCKED with evidence. Do not misuse a foreground-service type or silently substitute unreliable background execution.
- The default notification and widget are generic (`Pi is working`, completed, failed, connection lost). Session title/model/tool count are opt-in and use `Notification.VISIBILITY_PRIVATE` plus a generic public version. Never show prompt/assistant text, cwd, path, token, session ID, client/lock ID or share reference.
- “Stop monitoring” stops the local service/notification only; it is not “Abort run.” Do not add a destructive notification action without a separate confirmed product decision.
- Persist only a bounded continuity lease: enabled flag, local host profile ID, internal session ID, start/expiry timestamps and privacy preference. No token/path/cwd/transcript. On process restart, retrieve the Keystore token, perform an authenticated remote session-index refresh, require exactly one matching internal session ID, then resume through Plan 012's local `SessionKey` path. Do not create/use a public share reference for notification continuity. Reacquisition is allowed only while the explicit unexpired lease exists; otherwise fail closed.
- Completion notification is best-effort while the process/service observes events. Force-stop, quota termination and some OS kills cannot be guaranteed without push. On next app open, authenticated state resync reconciles terminal state and clears stale notifications.
- The widget displays one current/recent active session only. Multi-workspace widget selection belongs after Plan 017.

## Scope

**In scope**:

- `app/src/main/AndroidManifest.xml`
- a new application class and application-scoped graph wiring under `app/src/main/java/com/ayagmar/pimobile/**`
- `MainActivity.kt`, `PiMobileApp.kt`, `AppGraph.kt`
- focused continuity/notification/widget classes and narrowly related `SessionController` state
- `app/src/main/res/**` for channel/widget/provider resources
- `app/build.gradle.kts` only for a stable officially compatible AndroidX dependency that is justified by the platform decision
- focused unit/source/androidTest compilation tests with fakes; no real service/network/clock in unit tests
- `README.md`, `docs/architecture.md`, `docs/testing.md`, `docs/release.md`, dependency/ADR documentation
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- FCM, server/cloud push relay, accounts or remote notification credentials.
- Guaranteed delivery after force-stop/process death.
- Multiple host monitor connections or multi-workspace widget UI.
- Automatic lock takeover, a second bridge/controller/transport, or transcript previews by default.
- Device/emulator/ADB/connected acceptance.

## Steps

### Step 1: Resolve the API 37 service decision gate

Read official Android API 37 foreground-service, notification, background-start, process lifecycle and AppWidget guidance. Record required manifest permissions/type, start eligibility, quota/time limits, stop behavior and notification-permission UX. Map GO/LIMITED/NO-GO exactly as above. Do not write runtime code before this decision is recorded.

**Verify**:

```bash
rg -n 'foreground service|API 37|GO|LIMITED|NO-GO|quota|time limit' docs plans/014-background-turn-continuity-and-widget.md
```

Expected: official source links and one explicit outcome exist. NO-GO ends execution with plan status BLOCKED, not DONE.

### Step 2: Establish one application-scoped controller and lease

Move graph ownership to a custom `Application` (or an equally single-instance documented application container) and make Activity/service reuse it in the same process. Update Activity destruction: disconnect only when neither UI nor an active continuity lease owns the controller. Define/test deterministic ownership transitions for Activity recreate, task finish, service start/stop, duplicate starts and app process recreation.

Add the injected lease store and sanitized runtime snapshot. Snapshot fields are local target, coarse connection/run phase, monotonic elapsed basis, optional privacy-approved label and last update. Serialization/redaction tests must prove forbidden fields are absent. Corrupt/expired leases fail closed and clear stale notification/widget state.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: tests prove one controller instance, ownership/disconnect rules, lease expiry/corruption and snapshot redaction without touching real Keystore/preferences/network.

### Step 3: Add opt-in foreground continuity

Implement the GO/LIMITED service contract around the existing controller. Start only from an eligible user action while a run is active. Handle notification permission denial, service-start failure, reconnect grace, token loss, lock loss, bridge disconnect, agent settled/error, app task removal, quota expiry and process restart with an unexpired lease.

Use the existing client identity and Plan 012 authenticated resolve/resume path. Never silently acquire a different session or endpoint. If resume/lock acquisition fails, stop monitoring and show a generic actionable notification. On terminal state cancel ongoing notification and post completion/failure only once.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: fake lifecycle/clock/controller tests cover every transition, duplicate terminal events, quota expiry and no second transport; source tests compile/lint cleanly.

### Step 4: Add exact privacy-safe notification routing

Create stable channels and immutable explicit `PendingIntent`s with collision-safe request codes. Notification taps carry local `SessionKey` extras to Plan 012's common coordinator; they do not expose an external URI. Add generic lock-screen public versions and privacy preference behavior. “Stop monitoring” clears lease/service and states that the remote Pi run may continue; Open restores the exact session. Reconcile stale notifications on next authenticated app start.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: cold/warm intent tests, request-code uniqueness, tampered/missing extras, privacy variants, exactly-once terminal notifications and stale reconciliation pass.

### Step 5: Add the optional current-session widget

After Steps 1–4 are green, implement a minimal widget backed only by the sanitized local snapshot. Render no active session, working, completed/failed, connection lost and stale/expired states. The click uses the same immutable local-target intent. The widget does not create a graph, access a token, open a socket or start monitoring by itself. Update it from bounded lifecycle events and clear it on lease expiry/profile deletion.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: widget tests cover every state, privacy mode, tampered target and removal; no secret/path/session ID is rendered or logged.

### Step 6: Document limitations and operator-owned acceptance

Document best-effort semantics, selected service type/quota, permission denial, process death/force-stop limitations, next-open reconciliation and “Stop monitoring” behavior. Add but do not run device checks for screen off, task finish, rotation, process recreation, quota/timeout, reconnect, lock conflict, token loss, completion/failure and widget refresh. Mark evidence pending.

**Verify**:

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git status --short
```

Expected: commands pass; a `git diff --stat <recorded Plan 014 baseline>..HEAD` over the scope shows only in-scope implementation/tests/docs/plan files.

## Regression expectations

- Existing controller streaming/reconnect/resync/generation tests remain green.
- New tests cover one graph/controller, lease persistence/expiry, Activity/service ownership, every service terminal and platform-limit branch.
- Notification tests cover immutable intent identity, privacy/public versions and exactly-once delivery.
- Widget tests use controlled snapshot fakes only.

## Done criteria

- [ ] Official-source decision gate records GO or LIMITED; NO-GO leaves the plan BLOCKED.
- [ ] Tests prove Activity and service share one app-scoped controller/transport and disconnect ownership is deterministic.
- [ ] Lease/snapshot tests prove no token/path/cwd/transcript/share reference is persisted or rendered.
- [ ] Service tests prove opt-in start, quota/expiry, restart, reconnect, lock/token loss and exactly-once terminal handling.
- [ ] Notification/widget tests prove local-target routing, immutable/collision-safe intents and privacy-safe rendering.
- [ ] Docs state best-effort—not guaranteed—delivery after process death and include pending device evidence.
- [ ] Complete non-device commands exit 0; status shows only in-scope changes.

## STOP conditions

Stop if:

- No lawful API 37 service type supports even the LIMITED contract.
- Activity/service cannot share one controller without a duplicate WebSocket or separate process.
- Restart would require plaintext token, path/cwd persistence or reacquisition outside an explicit lease.
- Product wording would claim guaranteed post-death delivery without push.
- Notification/widget privacy cannot be enforced before rendering.
- A verification command fails twice after a reasonable correction.
