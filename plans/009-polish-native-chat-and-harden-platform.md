# Plan 009: Polish native chat and harden the platform

> Execute sequentially. Preserve Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd, Pi 0.80.6 compatibility, cwd/session locks, cursor synchronization, and the flat timeline mutation path. Do not run an emulator, connected tests, adb, APK installation, or manual device acceptance until the operator explicitly says **debug mode**.
>
> Drift baseline: `0960b87`. Before continuing, run `git diff --stat 0960b87..HEAD -- app bridge core-net core-rpc core-sessions docs plans` and reconcile current work with this plan.

## Status

- **State**: IN PROGRESS
- **Priority**: P1
- **Effort**: XL
- **Depends on**: Plan 008 implementation
- **Planned at**: `0960b87`

## Product outcome

Pi Mobile should feel like a deliberate Material 3 remote-agent client rather than a generic transcript viewer: quiet synchronization, stable reading, useful activity hierarchy, first-class image handling, accessible controls, explicit delivery state, bounded detail surfaces, current dependencies, and a bridge that remains safe and diagnosable under large sessions.

## Confirmed audit findings

1. `ChatViewModel.loadInitialMessages` treated any invalidation history change as cross-device editing, so ordinary local messages generated repeated Sync warnings.
2. Embedded images were decoded synchronously during composition and decoded again for thumbnails/full-screen previews.
3. Timeline reading state and sticky behavior were not saveable across configuration recreation, and unread activity did not distinguish answers from tools.
4. Six chat surfaces used deprecated `LocalClipboardManager`; Compose Android tests used deprecated JUnit4 rule APIs.
5. Several explicit chat icon buttons forced touch targets below Material's 48dp minimum.
6. Dependency declarations diverged across modules and lagged the newest stable versions compatible with AGP 8/API 36.
7. `ChatViewModel.kt`, `ChatTimeline.kt`, `ChatScreen.kt`, `bridge/src/server.ts`, and `bridge/src/session-indexer.ts` remain high-churn hotspots.
8. `bridge/src/session-indexer.ts` reads and splits complete session files independently for freshness, indexing, and inactive tree projection; large sessions multiply allocation and parse work.
9. The app has no window-size-class navigation path; the global phone drawer and hard-coded top padding are not tablet/foldable quality.
10. Tool output remains inline and can expand into a very large LazyColumn row; output copy and JSON inference are incomplete.

## Scope

In scope: chat UI/state/tests, Android dependency declarations and deprecated APIs, theme/navigation adaptivity, bridge/server correctness and large-session performance, docs, and focused tests. Out of scope: new undocumented Pi RPC commands, exposing arbitrary laptop files, changing lock semantics, image steer/follow-up, or deleting process isolation.

## Steps

### 1. Eliminate false sync conflicts

- Remove history-signature heuristics from connection/invalidation reloads.
- Only explicit other-client lock ownership may present a conflict warning.
- Reload failures remain actionable; successful invalidation and reconnect reloads are silent.
- Add a ViewModel regression test that changes history, emits `timelineInvalidated`, and proves no cross-device warning/notification appears.

### 2. Modernize compatible dependencies and deprecated APIs

- Upgrade all modules consistently to the newest stable versions that compile on AGP 8.13.2/API 36.
- Keep navigation on stable `2.9.8`; do not adopt alpha artifacts.
- Migrate `LocalClipboardManager` to suspend `LocalClipboard`/`ClipEntry` APIs.
- Migrate Compose tests to `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- Defer AGP 9/API 37/Kotlin built-in migration to a separate future task; do not suppress AAR metadata checks in this plan.

### 3. Make image preview production-grade

- Decode embedded base64 off the main thread and retain only bounded compressed-byte cache entries.
- Let Coil downsample bitmap decoding to the rendered constraints.
- Show loading and failure states, dimensions, encoded size, MIME type, and a safe display name.
- Add pinch zoom/pan using the current centroid-aware Transformable API.
- Add Storage Access Framework save and FileProvider-backed share actions.
- Restrict FileProvider to `cache/shared-images/`; never expose arbitrary files.
- Add parsing/formatting tests and compile Android test sources.

### 4. Clarify and preserve reading state

- Save sticky/paused state and reply/tool unread counters through configuration recreation.
- Show restrained `Following live` feedback only during an active followed run.
- Show `Paused · N replies · M tools` when reading is paused.
- Detail/image interactions pause following; only Jump/Latest or an intentional user scroll to bottom resumes it.
- Keep prepend anchor preservation and ensure loaded history never counts as unread.

### 5. Accessibility and composer polish

- Enforce 48dp interactive targets and add merged state semantics to thinking/tool disclosures.
- Give thumbnails and image actions specific labels.
- Make Send announce `Send message`, `Send as follow-up`, or `Send as steer`.
- Add an `isDispatchingMessage` state so local RPC dispatch has lightweight progress and failure recovery.
- Persist drafts through navigation/process recreation using `SavedStateHandle`; never store image bytes or tokens in saved state.
- Keep follow-up default. Queue reordering is forbidden unless Pi exposes a documented queue mutation RPC; the current local inspector is observational only.

### 6. Move heavy tool details out of the timeline

- Keep compact, semantic activity rows inline.
- Open non-trivial arguments/output/diff in a Material 3 bottom sheet keyed to one tool.
- Keep errors visible and compact; do not auto-open a blocking modal.
- Add copy actions for output and arguments, JSON inference, bounded syntax highlighting, and existing diff rendering.
- Avoid placing unbounded selectable output inside a single LazyColumn turn row.

### 7. Adapt navigation and theming

- Introduce Material window-size classes: compact drawer/navigation, expanded rail or permanent drawer.
- Replace hard-coded global top padding with app-bar/window inset ownership.
- Verify edge-to-edge, IME, landscape, multi-window, large font, dark theme, and predictive Back source contracts.
- Preserve the Pi blue identity; use dynamic color only behind an explicit preference if added.

### 8. Harden mobile and bridge hotspots

- Characterize bridge message ordering; serialize per-client control-envelope handling where races are possible while preserving RPC request correlation.
- Replace repeated whole-file freshness parsing with a bounded streaming/tail strategy, retaining authoritative fallback and existing sanitized errors.
- Ensure client-facing bridge errors never expose filesystem/internal exception details.
- Split `bridge/src/server.ts` handlers and continue decomposing ChatViewModel by owned state machines without changing architecture.
- Add tests before each behavior change.

## Verification

Focused:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.chat.*' --tests 'com.ayagmar.pimobile.ui.chat.*'
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm run check)
```

Full non-device gate:

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
```

If npm returns HTTP 410 for the retired audit endpoint, record the external failure verbatim and do not represent the audit as passing. Do not weaken tests, lint, AAR metadata checks, authentication, or session safety.

## Done criteria

- [ ] Local invalidations never produce false sync/conflict warnings.
- [ ] Image preview supports async bounded loading, metadata, zoom/pan, save, and share.
- [ ] Reading state survives configuration recreation and distinguishes reply/tool unread activity.
- [ ] Deprecated clipboard and Compose test APIs are gone.
- [ ] Composer exposes delivery and dispatch state without losing drafts.
- [ ] Heavy tool details use a bounded detail surface with copy/highlighting.
- [ ] Compact and expanded layouts follow Material 3 navigation/inset rules.
- [ ] AGP 9/API 37/Kotlin built-in migration remains deferred to a separate future task.
- [ ] Bridge/mobile P1 audit findings have regression coverage.
- [ ] Full non-device gates pass; device acceptance remains operator-owned.

## STOP conditions

- Stop if a requested queue operation, remote file preview, or image steer requires an undocumented RPC shape.
- AGP 9/API 37 migration is out of scope here and must be handled as a separate task.
- Stop if freshness noise can only be removed by ignoring explicit other-client ownership.
- Stop before any device/emulator command unless the operator says **debug mode**.
