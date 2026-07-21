# Plan 011: Migrate to AGP 9/API 37/JDK 25 and modernize the platform

> **Executor instructions**: This is a fresh-chat handoff plan. Assume you have no context beyond this file and the repository. Read this plan completely before editing. Execute the steps in order, keep the working tree verifiable after each phase, and update `plans/README.md` when the plan is complete or blocked. Do not stop at a toolchain compile if the quality, UI/UX, bridge, performance, and documentation gates below have not also run.
>
> Preserve the architecture: Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd. Do not migrate the bridge to the Pi SDK, remove cwd/session locks, alter the WebSocket envelope casually, invent Pi RPC commands, expose arbitrary laptop files, or weaken cursor synchronization and the exactly-one full rebuild rule.
>
> **Drift check (run first)**:
>
> ```bash
> git status --short --branch
> git rev-parse --short HEAD
> git diff --stat 2149432..HEAD -- \
>   build.gradle.kts settings.gradle.kts gradle gradle.properties \
>   app core-net core-rpc core-sessions benchmark \
>   bridge .github docs plans
> ```
>
> The plan was written against commit `2149432`. The current branch is expected to contain later performance/UI commits; if the drift check shows changes, reconcile the live code and preserve their behavior before changing the toolchain.

## Status

- **State**: BLOCKED — no stable detekt release supports the required JDK 25/Gradle 9 matrix
- **Priority**: P1
- **Effort**: XL
- **Risk**: HIGH
- **Depends on**: current non-device completion of Plans 009 and 010; device acceptance is not a prerequisite
- **Category**: migration / tech-debt / quality / performance / UI
- **Planned at**: commit `2149432`, 2026-07-13
- **Issue**: omit unless explicitly published with `--issues`

## Why this matters

Pi Mobile is currently on an AGP 8.13.2, Gradle 8.14.5, Kotlin 2.2.21, API 36, and JDK 21 baseline. The next platform baseline is deliberately separate from the previous feature/performance work: AGP 9, Android API 37, Java/JDK 25, the AGP 9 built-in Kotlin integration model, current AndroidX/Compose dependencies, and a refreshed bridge toolchain must be migrated as one compatibility program. The result must be reproducible in CI, preserve the authenticated bridge/process architecture, remove deprecated APIs without broad lint suppression, improve large-screen and edge-to-edge behavior, and retain deterministic non-device verification.

This plan is intentionally end to end. A successful executor must produce a documented compatibility matrix, green Android/bridge gates, an updated CI toolchain, migration regression coverage, modern Material 3 adaptive UI, and an explicit record of anything that remains operator-owned because it requires a device.

## Current state

### Repository and architecture

- `app/` — Compose Android application, ViewModels, secure host/token storage, navigation, chat/session UI.
- `core-rpc/` — typed Pi RPC commands/events and sanitized compatibility fixtures.
- `core-net/` — authenticated WebSocket transport, request correlation, reconnect/resync, and entry cursors.
- `core-sessions/` — cached session-index models/repository.
- `benchmark/` — Android macrobenchmark/profile scaffolding targeting `:app`.
- `bridge/` — TypeScript authenticated WebSocket bridge, cwd/session locks, one Pi subprocess per cwd, session indexing, inactive tree reads, and internal extensions.

The architectural boundary is documented in `docs/architecture.md`, `docs/bridge-protocol.md`, ADR-0001/ADR-0003, and the repository `AGENTS.md`. Active-session reads remain documented Pi RPC calls (`get_state`, `get_messages`, `get_entries`, `get_tree`); inactive session browsing remains bridge-owned.

### Current Android/toolchain declarations

- `build.gradle.kts:7-16` currently declares AGP `8.13.2`, Kotlin `2.2.21`, Compose plugin `2.2.21`, Kover `0.9.8`, ktlint `12.1.1`, and detekt `1.23.8`.
- `gradle/wrapper/gradle-wrapper.properties` currently uses Gradle `8.14.5`.
- `app/build.gradle.kts:13-18` uses `compileSdk = 36`, `targetSdk = 36`, and `minSdk = 26`.
- `app/build.gradle.kts:35-40` uses Java/Kotlin JVM 21.
- `benchmark/build.gradle.kts:10-23` also uses compile/target SDK 36 and Java/Kotlin 21.
- `core-net/build.gradle.kts`, `core-rpc/build.gradle.kts`, and `core-sessions/build.gradle.kts` each use `jvmToolchain(21)`.
- Root `build.gradle.kts:26` configures detekt with `jvmTarget = "21"`.
- There is no `gradle/libs.versions.toml`; dependency versions are declared directly in module build scripts. Do not introduce a version catalog automatically. First determine whether AGP 9 compatibility is clearer with the current layout; consolidate only if it reduces drift without a broad unrelated refactor.

### Current dependency matrix and known direction

`docs/dependency-matrix.md` records the previous modernization as AGP 8.13.2/Gradle 8.14.5/JDK 21/Kotlin 2.2.21/API 36 and explicitly says AGP 9/API 37 is deferred. This plan supersedes that deferral. Update the matrix only after verifying official compatibility sources at execution time; do not copy speculative versions into the matrix.

Current important dependencies include:

- Compose BOM `2026.06.00`, Material 3, Navigation Compose `2.9.8`.
- AndroidX Core KTX `1.18.0`, Activity Compose `1.12.4`, Lifecycle `2.10.0`.
- OkHttp `5.4.0`, Kotlin serialization JSON `1.11.0`, Coil Compose `2.7.0`.
- Diff Utils `4.17`, Prism4j `2.0.0`, Google Code Scanner `16.1.0`.
- Benchmark module still declares older `androidx.test` and benchmark dependencies (`benchmark-macro-junit4:1.2.4`) and must be checked independently for AGP 9/API 37 compatibility.

### Current CI and verification

- `.github/workflows/ci.yml:20-28` installs Java 21 and Android SDK/platform 36/build-tools 36.0.0, then runs the Android static/unit/lint/assembly gate.
- `.github/workflows/ci.yml:44-55` runs Node 22, pnpm 10.33.0, bridge checks, and production audit.
- `AGENTS.md` requires JDK 21, Node 22+, pnpm 10, Android SDK 36, and Pi 0.80.6+. This plan changes the Android requirement to the verified JDK 25/API 37/AGP 9 matrix while retaining Node 22+ and Pi 0.80.6 minimum compatibility unless a separate bridge decision is documented.
- Required non-device gates currently are:

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
git status --short
```

Do not run an emulator, connected Android tests, `adb`, `installDebug`, APK installation, screenshots, or manual device testing unless the operator explicitly says `debug mode`.

## Authoritative compatibility research rule

Before changing the first build file, the executor must verify and record the exact stable versions that are mutually compatible at execution time. Use official sources only for platform compatibility:

1. Android Developers AGP release notes and AGP/Gradle compatibility table.
2. Gradle compatibility and Java 25 support documentation.
3. Kotlin release notes and AGP 9 built-in Kotlin migration documentation.
4. Android API 37 setup/release documentation.
5. Kotlin Compose compiler migration documentation.
6. Official AndroidX/Compose release notes for dependencies actually used.
7. GitHub Actions/setup-java and Android SDK action documentation for CI syntax.

Do not choose a version because it is merely the newest registry entry. Record a table with `previous`, `selected`, `reason`, `required JDK`, `required Gradle`, `required compile/target SDK`, and `source URL`. If AGP 9, Gradle, Kotlin, or JDK 25 cannot be installed in the local/CI environment, stop before source migration and report the exact incompatibility.

## Scope

### In scope

- Root Gradle plugin and wrapper migration to verified AGP 9/Gradle/JDK 25 compatibility.
- Android API 37 compile/target migration and required platform behavior changes.
- AGP 9 built-in Kotlin integration and Kotlin/JVM 25 migration across all Kotlin modules.
- Compose compiler, Compose BOM, AndroidX, Navigation, lifecycle, benchmark, test, lint, detekt, ktlint, Kover, OkHttp, serialization, Coil, Prism, diff, and scanner dependency convergence where compatible.
- CI toolchain updates, cache correctness, dependency validation, and reproducible setup documentation.
- Deprecated Android/Compose API removal, edge-to-edge/insets, predictive Back, adaptive Material 3 navigation, large-screen behavior, accessibility, and related regression tests.
- Measured performance and quality improvements that are directly enabled by the migration, including Compose first-frame instrumentation, benchmark profile compatibility, and bounded expensive work.
- Bridge Node/TypeScript dependency and quality updates that can be verified without changing the bridge protocol or process architecture.
- `docs/dependency-matrix.md`, `docs/testing.md`, `docs/release.md`, `README.md`, `.github/workflows/ci.yml`, `AGENTS.md`, and this plan's status/evidence.

### Explicitly out of scope

- Pi SDK migration.
- Changing the authenticated WebSocket envelope or adding undocumented Pi RPC commands.
- Removing cwd/session locks, cursor synchronization, process isolation, or root/path security checks.
- Changing session-file semantics or guessing private Pi behavior.
- Enabling release minification solely because the toolchain changed; keep it separate unless device release validation is operator-owned.
- New product features unrelated to migration quality, performance, accessibility, or adaptive UI.
- AGP 9 migration work in any other repository.
- Device acceptance before the operator says `debug mode`.

## Git workflow

- Continue on the current feature branch unless the operator creates a dedicated migration branch.
- Use small Conventional Commits, matching existing history, for each verified phase. Examples: `chore(build): migrate to agp 9`, `chore(android): target api 37`, `chore(kotlin): adopt jvm 25`, `fix(ui): modernize edge to edge navigation`, `test(build): cover migration compatibility`.
- Never push, merge, rebase, reset, rewrite history, or open a PR.
- Do not commit generated build outputs, tokens, `.env` files, credentials, private sessions, or raw private paths.

## Steps

### Step 0: Establish a migration baseline and reconcile current work

1. Read `AGENTS.md`, `plans/README.md`, Plans 009 and 010, `docs/architecture.md`, `docs/bridge-protocol.md`, `docs/testing.md`, ADR-0003, `docs/dependency-matrix.md`, and the current Pi RPC docs before editing.
2. Run the drift check at the top of this plan.
3. Record the current outputs of:

```bash
java -version
gradle --version
./gradlew --version
node --version
pnpm --version
./gradlew tasks --all > /tmp/pi-mobile-gradle-tasks.txt
```

Do not include private paths or credentials from output in committed documentation.
4. Run the current non-device baseline before changing it. If it fails, classify each failure as pre-existing or migration-related and record it; do not hide it by changing lint baselines or weakening gates.

**Verify**: the baseline commands either pass or have a written, sanitized failure inventory, and the working tree is clean before migration edits.

### Step 1: Select and document the exact AGP 9/JDK 25/API 37 matrix

1. Verify the stable AGP 9 release, matching Gradle version, required JDK version, Kotlin integration model, Android SDK/platform 37, and build-tools version from official sources.
2. Verify the selected Kotlin version and Compose compiler plugin model. AGP 9's built-in Kotlin integration must be followed as documented; do not blindly retain both the old Kotlin Android plugin and built-in Kotlin wiring.
3. Verify every plugin used by this repo: Kover, ktlint, detekt, serialization, Compose, and Android benchmark. Record compatibility or upgrade requirements.
4. Verify the selected AndroidX/Compose/test versions against API 37 and Kotlin/JDK 25. Keep Navigation Compose on a stable release; do not adopt an alpha solely to make compilation pass.
5. Update `docs/dependency-matrix.md` with the verified previous→selected table and source links. Include a separate “known unavailable/blocked” subsection if the local environment cannot install a selected component.

**Verify**:

```bash
./gradlew --version
java -version
```

Expected: the selected Gradle distribution runs on JDK 25 and the matrix cites official compatibility sources. No build file changes are made until this step is recorded.

### Step 2: Migrate Gradle, AGP 9, and built-in Kotlin integration

1. Update `gradle/wrapper/gradle-wrapper.properties` to the Gradle version required by the selected AGP 9 release.
2. Update root plugin declarations in `build.gradle.kts`.
3. Follow the official AGP 9 built-in Kotlin migration exactly. Remove obsolete Kotlin Android/JVM plugin applications only where AGP 9 now owns that integration; retain the Kotlin serialization and Compose compiler plugins in their supported form.
4. Update `app/build.gradle.kts`, `benchmark/build.gradle.kts`, `core-net/build.gradle.kts`, `core-rpc/build.gradle.kts`, and `core-sessions/build.gradle.kts` to the supported AGP 9 DSL.
5. Keep `namespace`, `compileSdk`, build types, test configuration, packaging exclusions, and module boundaries unchanged unless the migration requires a syntax change.
6. Do not add convention plugins or a version catalog in this step. First get the existing build structure green.

**Verify**:

```bash
./gradlew help
./gradlew :app:compileDebugKotlin :core-net:compileKotlin :core-rpc:compileKotlin :core-sessions:compileKotlin
```

Expected: Gradle configuration succeeds without deprecated Kotlin plugin warnings, and all production Kotlin modules compile.

### Step 3: Move every JVM target to Java 25

1. Change Android `compileOptions` and Kotlin compiler/JVM target settings to the supported Java 25 configuration in `app` and `benchmark`.
2. Change `jvmToolchain(21)` to the verified JDK 25 toolchain in `core-net`, `core-rpc`, and `core-sessions`.
3. Change root detekt `jvmTarget` and any test/compiler target configuration to 25.
4. Search the entire repository for `VERSION_21`, `JVM_21`, `jvmToolchain(21)`, `java-version: 21`, and prose claiming JDK 21 is required. Update only Android/toolchain references that belong to this migration; retain Node 22+ and Pi 0.80.6 requirements.
5. Ensure tests do not rely on a local JDK accidentally. Gradle toolchain resolution must be explicit and CI must install the same major JDK.

**Verify**:

```bash
rg -n 'VERSION_21|JVM_21|jvmToolchain\(21\)|java-version: 21|JDK 21|Java 21' \
  build.gradle.kts app core-net core-rpc core-sessions benchmark .github README.md docs AGENTS.md
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
```

Expected: no stale active JDK 21 declarations remain; compile, unit-test, and Android-test Kotlin compilation use Java 25.

### Step 4: Raise Android API 37 and migrate platform behavior

1. Set app and benchmark `compileSdk` and `targetSdk` to verified API 37/platform values.
2. Install the exact SDK/platform/build-tools locally and in CI; do not silently compile against a different installed platform.
3. Run lint with warnings as errors and inspect every new API 37 warning. Do not expand `lint-baseline.xml`, disable new checks, or disable `OldTargetApi`/other checks to mask migration work.
4. Audit edge-to-edge behavior, status/navigation bars, IME insets, display cutouts, foldable/large-window sizing, and back dispatch under the API 37 behavior changes.
5. Replace deprecated Android APIs in touched areas with current platform/AndroidX APIs. Keep Keystore token protections, disabled backup behavior, and cleartext host restrictions intact.

**Verify**:

```bash
./gradlew :app:lintDebug :app:lintRelease
```

Expected: no new warnings/errors and no unjustified lint-baseline growth. Record any unavoidable baseline entry with an explanation and a follow-up owner; otherwise stop and fix it.

### Step 5: Converge dependencies and remove migration debt

1. Build a dependency report for all modules and identify duplicate/divergent declarations. Do not upgrade blindly; select versions compatible with the Step 1 matrix.
2. Upgrade Compose BOM, Material 3, Activity, Lifecycle, Navigation, Core KTX, test libraries, benchmark libraries, OkHttp, Kotlin serialization, Coil, diff-utils, Prism4j, scanner, Kover, ktlint, detekt, and serialization plugins as required.
3. Keep one stable Navigation Compose line. Do not add alpha/RC artifacts without an explicit written compatibility reason.
4. Migrate Compose tests to current JUnit4/v2 APIs where still present and remove deprecated clipboard APIs. Search for `LocalClipboardManager`, old Compose test rule creation, and deprecated Android APIs.
5. Upgrade bridge Node/TypeScript dependencies only after Android migration is green. Use a supported Node LTS, pnpm 10+, frozen lockfile, ESLint/typecheck/Vitest, and production audit. Do not change protocol payloads as part of dependency upgrades.
6. Decide explicitly whether direct declarations or a version catalog is safer. If a catalog is introduced, migrate all modules in one verified step and do not leave duplicate version sources.

**Verify**:

```bash
rg -n 'LocalClipboardManager|createComposeRule|VERSION_21|JVM_21|deprecated' app core-net core-rpc core-sessions benchmark
./gradlew dependencies --configuration debugRuntimeClasspath > /tmp/pi-mobile-debug-runtime-dependencies.txt
./gradlew :app:testDebugUnitTest :core-net:test :core-rpc:test :core-sessions:test
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

Expected: no targeted deprecated API matches, tests pass, and audit reports no reachable high/critical production vulnerability.

### Step 6: Modernize Compose UI/UX for API 37 and large screens

1. Use the current stable Material 3 adaptive/window-size APIs selected in Step 1. Implement compact navigation with a drawer or compact suite and expanded navigation with the rail/permanent surface; keep the Pi blue identity and existing semantic labels.
2. Replace hard-coded global padding with app-bar and window-inset ownership. Verify `Scaffold`/`TopAppBar`/IME behavior and avoid double insets.
3. Implement predictive Back using the current AndroidX Activity/Compose back APIs. Back behavior must be explicit for modal sheets, drawers, dialogs, command palette, image preview, and navigation stack.
4. Audit every interactive control for at least 48dp touch targets, content descriptions, state semantics, keyboard/focus order, TalkBack labels, large font, contrast, and error/recovery actions.
5. Keep large tool output in bounded detail surfaces. Preserve the new tool bottom sheet, bounded output, copy actions, and non-blocking error presentation.
6. Do not introduce a generic decorative redesign. Reuse existing `PiMobileTheme`, design-system components, spacing, blue identity, and chat hierarchy.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.ui.*' --tests 'com.ayagmar.pimobile.chat.*'
./gradlew :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: UI/state tests pass, Android test sources compile, and lint finds no accessibility/inset/back regressions. Device TalkBack/rotation/large-window evidence remains `PENDING — operator-owned` until `debug mode`.

### Step 7: Finish quality and performance improvements enabled by the new baseline

1. Preserve the controller-owned staged bootstrap, generation checks, tree caches, bounded tail freshness, transport reuse, and bridge control-plane serialization already present at this handoff.
2. Measure first-frame Compose work before changing it. Add debug/sanitized timing around projection, turn construction, diff computation, syntax highlighting, image metadata, and first timeline frame. Never log message text, tool output, image bytes, session paths, tokens, client IDs, or credentials.
3. Cache stable projections by session generation/turn identity. Keep stable LazyColumn keys and avoid rebuilding unchanged turns.
4. Keep diff/JSON/highlight work off the main thread, bounded by explicit output limits, with cache eviction and cancellation when switching sessions.
5. Confirm the existing `DiffViewer` cache and background computation remain correct under API/Kotlin migration; add tests for cache hits, invalidation, cancellation, and bounded output rather than rewriting it wholesale.
6. Update the macrobenchmark/profile scaffolding only after the AGP 9/API 37 benchmark plugin is compatible. Do not run connected macrobenchmarks without `debug mode`.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.chat.*' --tests 'com.ayagmar.pimobile.ui.chat.*'
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :benchmark:tasks --all
```

Expected: deterministic unit tests cover caches/cancellation/bounds; benchmark tasks configure successfully. Connected benchmark execution remains operator-owned.

### Step 8: Harden bridge and runtime quality after dependency migration

1. Preserve authenticated host isolation, one process per cwd, control locks, reconnect grace, error sanitization, and root/path validation.
2. Add lifecycle tests for rapid `bridge_set_cwd`, control acquisition/release, switch response, RPC request, tree request, invalidation, disconnect, and reconnect sequences.
3. Ensure control-plane serialization does not serialize or drop correlated RPC responses/events unnecessarily.
4. Audit all bridge logs and client errors for paths, tokens, authorization headers, raw client/lock IDs, exception details, message text, session content, and credentials. Keep only sanitized operation names, durations, bytes, counts, cache state, and statuses.
5. Run Node/TypeScript static analysis and production audit after lockfile changes. Do not change bridge protocol shape for convenience.

**Verify**:

```bash
(cd bridge && pnpm run check && pnpm audit --prod)
rg -n 'console\.|logger\.(info|warn|error|debug)|sessionPath|clientId|token|Authorization' bridge/src bridge/test
```

Expected: all tests pass; any remaining sensitive-looking log site is either removed, sanitized, or explicitly justified in a code comment and review note. Never print matched secret values.

### Step 9: CI, developer environment, and release documentation

1. Update `.github/workflows/ci.yml` to install the verified JDK 25, Android SDK/API 37 platform/build-tools, verified Gradle/AGP-compatible caches, Node LTS, and pnpm version.
2. Keep CI split into Android and bridge jobs. Add explicit toolchain version checks so an accidental JDK/SDK fallback fails early.
3. Update `AGENTS.md`, `README.md`, `docs/testing.md`, `docs/release.md`, `docs/revival-acceptance.md`, `docs/final-acceptance.md`, `docs/codebase.md`, and `docs/dependency-matrix.md` with the selected matrix and exact commands.
4. Document that device acceptance, connected tests, macrobenchmarks, TalkBack, rotation, API 37 behavior, and release smoke remain operator-owned until `debug mode`.
5. Record migration compatibility notes, rejected alternatives, and any dependency limitations in an ADR or the dependency matrix. Do not leave the old AGP 8/JDK 21 guidance active in contributor docs.

**Verify**:

```bash
rg -n 'AGP 8|compileSdk = 36|targetSdk = 36|JDK 21|Java 21|VERSION_21|JVM_21|API 36' \
  README.md AGENTS.md docs .github plans build.gradle.kts app core-net core-rpc core-sessions benchmark
```

Expected: no stale active baseline remains except historical migration tables explicitly labeled as historical. CI syntax and version checks are reviewed without needing a device.

### Step 10: Final non-device gate and handoff evidence

Run the complete gate from a clean working tree:

```bash
./gradlew clean ktlintCheck detekt test \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
git status --short --branch
git log -12 --oneline
```

Record in the plan/docs:

- selected AGP/Gradle/JDK/Kotlin/API/Compose/dependency versions and official sources;
- exact commands and pass/fail outcomes;
- migration-specific tests added and counts;
- sanitized performance/cache evidence;
- APK assembly results;
- bridge audit result;
- device-only validation marked `PENDING — operator-owned` with executable commands, never claimed as passed.

## Test plan

### Build and migration tests

- Gradle configuration and toolchain checks for all modules.
- Android app unit tests, all core module tests, lint, detekt, ktlint, debug/release assembly.
- Android test-source compilation without launching a device.
- Benchmark module configuration/task discovery under AGP 9/API 37.
- CI version checks for JDK 25, API 37, Node LTS, and pnpm.

### Runtime regression tests

- `RpcSessionControllerTest` and fake-controller tests for controller-owned bootstrap request counts, staged state callback, session switch timing boundaries, tree timeout configuration, and generation cancellation.
- `ChatViewModel` tests for saved draft restoration/clearing, first-frame bounded projection, stale session suppression, cached tree replacement, and late result rejection.
- Bridge session-index tests for streaming JSONL equivalence, small/medium/large fixtures, append-tail success, unsafe/malformed fallback, parse metrics, cache hits/misses, eviction, and revision invalidation.
- Bridge server tests for serialized control-plane ordering, reconnect/switch/tree races, sanitized errors, host isolation, and RPC response correlation.
- Compose/source tests for adaptive compact/expanded navigation, 48dp controls, semantic labels, modal Back handling, and bounded tool details.

### Device-owned tests after explicit `debug mode`

Do not run these during ordinary plan execution:

- API 37 emulator/device startup and edge-to-edge/insets behavior.
- Rotation, large font, TalkBack, predictive Back, IME, foldable/landscape/multi-window navigation.
- Long-session resume/switch/tree macrobenchmark and first-frame trace.
- Bridge disconnect/reconnect during switch/tree/streaming.
- Release-like APK launch and secure token migration behavior.

## Done criteria

- [ ] Official AGP 9/Gradle/JDK 25/Kotlin/API 37 compatibility matrix is recorded with sources.
- [ ] All Android and Kotlin modules build on JDK 25 with no active JDK 21 declarations.
- [ ] App and benchmark compile/target API 37 with no new lint errors or unjustified baseline growth.
- [ ] AGP 9 built-in Kotlin integration is used as documented; obsolete plugin wiring is removed.
- [ ] AndroidX/Compose/benchmark/test dependencies are converged and compatible; no unauthorized alpha/preview dependency was added.
- [ ] Bridge Node/TypeScript dependencies, lockfile, lint, typecheck, tests, and production audit pass.
- [ ] Controller bootstrap, parser tail fallback, tree cache, transport reuse/backoff, lifecycle serialization, and metrics regressions pass.
- [ ] Compose UI has compact/expanded navigation, app-bar-owned insets, predictive Back source contracts, bounded detail surfaces, and accessible controls.
- [ ] SavedStateHandle drafts and migration-sensitive secure storage behavior are covered by tests.
- [ ] First-frame performance work is measured, bounded, cancellable, and cached without transcript/privacy logging.
- [ ] CI and contributor/release docs use the selected toolchain and exact gates.
- [ ] Full non-device gate passes; device acceptance is explicitly `PENDING — operator-owned` until `debug mode`.
- [ ] `git diff --check` passes and the working tree is clean.

## STOP conditions

Stop and report instead of improvising if:

- AGP 9, Gradle, JDK 25, Kotlin, API 37, or the selected benchmark/plugin versions have no mutually compatible stable matrix.
- The local or CI environment cannot install the selected JDK 25/API 37/toolchain without bypassing verification.
- AGP 9 requires a plugin or dependency still incompatible with the repository and no documented stable upgrade exists.
- Kotlin built-in integration would require changing public RPC/data contracts or architectural boundaries.
- API 37 behavior appears to require weakening cleartext security, token storage, process isolation, lock semantics, or bridge authentication.
- A migration proposes suppressing lint/AAR metadata/deprecation warnings globally, skipping tests, or expanding a baseline without a specific reviewed finding.
- A dependency upgrade changes Pi RPC payloads, WebSocket envelopes, session-file semantics, or security boundaries.
- A UI change requires device-only evidence before a safe source/unit test can be added; isolate it as operator-owned instead.
- A benchmark/profile task would launch a device, emulator, connected test, `adb`, installation, or screenshot without the exact operator phrase `debug mode`.
- Any command or file would expose tokens, authorization headers, `.env` values, credentials, private sessions, raw client/lock IDs, private paths, or transcript content.

## Execution evidence (2026-07-17)

### Step 0 baseline

- Drift check: only this Plan 011 handoff and plan index differed from the plan baseline before execution.
- Local tools: JDK 21.0.10, Gradle 8.14.5, Node 24.12.0, and pnpm 10.33.0. JDK 25 distributions are installed locally; Android API 37 is not installed.
- `./gradlew tasks --all`: PASS.
- Existing Android gate: PASS (`clean`, ktlint, detekt, all unit tests, debug lint, debug APK, and release APK).
- Android test-source compilation: PASS.
- Bridge frozen install/check: PASS (7 files, 63 tests).
- Bridge production audit: PASS, no known vulnerabilities reported by the registry.
- No lint baseline was changed.

### Step 1 compatibility decision

The candidate stable platform matrix is documented in `docs/dependency-matrix.md`: AGP 9.1.1, Gradle 9.3.1, JDK/JVM 25, Kotlin/Compose compiler 2.4.10, and Android API 37. Newer AGP 9.3 was intentionally rejected because Kotlin 2.4.10 does not document it as fully supported.

Execution stopped before changing build files because the latest stable detekt (`1.23.8`) is documented against JDK 21 and Gradle 8.12.1. detekt's first documented JDK 25/Gradle 9 line is `2.0.0-alpha.1`, and the 2.0 line remains pre-release. Using it would violate this plan's stable-only rule; retaining stable 1.23.8 would violate the verified plugin compatibility and static-analysis JVM 25 requirements. This matches the plan STOP condition for a plugin with no documented stable compatible upgrade.

No Android SDK package, AGP/Gradle/Kotlin version, source, CI, dependency, bridge protocol, lint baseline, device, emulator, ADB, installation, screenshot, or private runtime state was changed or exercised.

Device-only validation remains **PENDING — operator-owned**. Evidence fields remain empty because execution stopped before migration and device commands are prohibited without `debug mode`.

## Maintenance notes

- Keep the compatibility matrix current whenever AGP, Gradle, Kotlin, JDK, API, Compose BOM, or benchmark versions change. Do not infer compatibility from a successful partial compile.
- The AGP 9 built-in Kotlin integration is a build-system decision; future module additions must follow the selected root/module pattern and not reintroduce obsolete Kotlin plugin application.
- The bridge remains a separate Node/TypeScript runtime. Android toolchain migration must not cause a protocol or Pi SDK migration.
- Performance caches require explicit revision keys and bounded eviction. Any future session/index/tree change must preserve stale markers, authoritative active Pi tree reads, and cancellation/generation checks.
- UI modernization must reuse the existing Pi theme/components and keep chat content hierarchy; review large fonts, semantics, Back, IME, and insets together rather than as isolated polish.
- Device-only evidence must remain clearly separated from non-device gates. Do not mark this plan DONE from unit tests alone.
