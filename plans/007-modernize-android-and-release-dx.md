# Plan 007: Modernize Android, CI, release checks, and contributor DX

> **Executor instructions**: Upgrade in small compatible increments using authoritative release notes. Record every version choice and run full gates after each group. Update `plans/README.md` when done.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- build.gradle.kts settings.gradle.kts gradle app core-net core-rpc core-sessions benchmark .github README.md docs AGENTS.md plans`

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/004-simplify-tree-and-incremental-sync.md`, `plans/006-redesign-onboarding-and-navigation.md`
- **Category**: dx
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

The Android stack is centered on 2024-era Kotlin, Compose, AGP, target SDK 34, and deprecated `security-crypto` alpha storage. CI skips connected UI tests and release assembly. The final revival milestone should establish a current, reproducible baseline rather than leave upgrades to the next feature change.

## Current state

- Root: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7.
- App: compile/target SDK 34, Compose BOM 2024.06.00, Navigation 2.7.7, `security-crypto:1.1.0-alpha06`, release minification disabled.
- Token store uses deprecated `EncryptedSharedPreferences`/`MasterKey`.
- CI runs `./gradlew :app:check`; it does not run connected tests or assemble release.
- Repository has no project `AGENTS.md`, version catalog, `.env.example` before plan 002, or single root verification command.

## Commands

Use project-supported JDK from current authoritative Android requirements. Final commands:

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

CI emulator command must run the small existing `androidTest` suite and exit 0.

## Scope

**In scope**: Gradle wrapper/build files and dependency organization, Android source/tests required by migrations, benchmark compatibility, CI, root contributor docs/scripts, README/testing docs, plan status.

**Out of scope**: unrelated dependency additions, Play Store publication, signing secrets, changing minSdk without explicit evidence, enabling minification without release smoke coverage.

## Steps

### Step 1: Research and record a compatible target matrix

Using official Android, Kotlin, Compose, and library release notes/registries, select latest stable mutually compatible versions as of execution time. Record current→target, compatibility rationale, migration links, and expected code impact in `docs/dependency-matrix.md`. Include Node/pnpm/Pi supported versions from earlier plans.

**Verify**: every upgraded dependency has an authoritative source and no alpha/beta choice unless unavoidable and justified.

### Step 2: Upgrade build foundation incrementally

Upgrade Gradle/AGP/JDK/compileSdk-targetSdk first, then Kotlin/Compose compiler integration, then AndroidX/Compose BOM and other libraries. Prefer a version catalog if it clearly reduces existing duplication; do not introduce convention plugins. Run compile/test/lint after each group and commit separately.

**Verify**: final full Gradle command passes without suppressing new warnings globally or expanding lint baseline.

### Step 3: Replace deprecated token storage safely

Design the smallest supported Android Keystore-backed storage path. Preserve existing encrypted tokens through an explicit one-time migration if technically possible. If reliable migration is impossible, STOP and request a product decision because silently losing tokens harms onboarding. Never log tokens; disable backup for secret material or add explicit backup exclusion rules as appropriate.

Tests cover new write/read/delete, migration, corrupted legacy data, and no plaintext storage.

**Verify**: `rg -n 'EncryptedSharedPreferences|security-crypto' app build.gradle.kts` returns no production matches after migration; security tests pass.

### Step 4: Strengthen CI

Add jobs/steps for:

- root Kotlin lint/detekt/unit tests across all modules,
- debug lint and debug/release assembly,
- bridge check and production audit,
- connected Android tests on a pinned emulator API with caching and timeout,
- artifact upload for debug APK on appropriate events if repository policy permits.

Use least-privilege permissions. Do not add signing credentials.

**Verify**: validate workflow syntax and run equivalent commands locally; all pass.

### Step 5: Add contributor and agent guidance

Create project `AGENTS.md` containing architecture invariants, exact setup/check commands, module boundaries, RPC compatibility policy, test placement, secret policy, and plan execution protocol. Add a root `Makefile` or simple checked-in script only if it genuinely provides one command for all checks; do not add a task framework dependency.

Update README/testing docs for clean checkout through first prompt and release verification.

**Verify**: a clean-shell command sequence in docs matches CI exactly.

### Step 6: Final end-to-end acceptance

Create `docs/revival-acceptance.md` with executable checks and measured results. Run on a real emulator/device and bridge:

- clean install/onboarding,
- connection failures and success,
- list/new/resume/rename/fork/tree/import/export/compact,
- prompt/image/stream/tool/thinking/abort/steer/follow-up,
- extension dialogs/widgets/status,
- retry/compaction/settled lifecycle,
- disconnect/reconnect/incremental resync/external edit,
- multiple hosts and lock contention,
- background/rotation/font scale,
- release APK launch.

Record actual device/API/Pi/bridge versions and pass/fail. Fix failures within the owning prior-plan architecture; do not mark done with TODO/TBD values.

## Test plan

All unit, lint, bridge, connected Compose, debug/release build, and manual acceptance gates are mandatory. Add regression tests for every migration bug discovered.

## Done criteria

- [ ] Stable compatible dependency matrix is documented.
- [ ] Android builds on a current supported toolchain and target SDK.
- [ ] Deprecated security-crypto storage is removed with safe migration.
- [ ] CI exercises unit, static, bridge audit, release, and connected tests.
- [ ] Project `AGENTS.md` and clean-checkout instructions exist.
- [ ] Revival acceptance has no unresolved failures/TBDs.
- [ ] Global completion gate in `plans/README.md` passes.
- [ ] Plan row is DONE.

## STOP conditions

- Latest stable versions are mutually incompatible; report the conflict and propose a pinned compatible matrix.
- Token migration cannot preserve credentials safely.
- Release build requires signing material not available in repository-safe configuration.
- Connected tests are flaky after two root-cause attempts; report evidence instead of hiding or retry-looping them.

## Execution note

The operator approved resetting legacy encrypted tokens because removing `security-crypto` makes its private storage format unavailable. Host profiles are preserved, only the legacy token preference file is removed once, and the app shows a restrained token re-entry notice. New tokens use platform Android Keystore AES-256-GCM.

Connected and manual device checks are `PENDING — operator-owned` under the superseding roadmap instruction. The complete executable procedure and evidence fields are in `docs/revival-acceptance.md`.

## Maintenance notes

Do not combine future framework upgrades with feature work. Keep the dependency matrix and CI emulator version current, and treat expanded lint baselines or skipped tests as regressions requiring explicit review.
