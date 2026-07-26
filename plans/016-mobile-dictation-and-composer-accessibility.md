# Plan 016: Add opt-in mobile dictation and composer accessibility polish

> Fresh-context executor: read this plan completely before editing. Dictation is draft input only: never auto-send, persist audio, record in the background, or reuse Pi/provider credentials for transcription.

## Status

- State: TODO
- Priority: P2
- Effort: M–L (approximately 1–2 focused engineering weeks)
- Depends on: Plans 012, 013 and 015; Plan 014 must be settled (DONE or BLOCKED) first to avoid manifest/app-lifecycle conflicts
- Category: mobile input / accessibility / privacy

## Objective

Add native push-to-talk speech recognition to the existing text composer, with explicit permission/privacy disclosure, deterministic lifecycle/cancellation, partial-result preview and final draft insertion. Preserve prompt/steer/follow-up behavior and improve keyboard, large-text and assistive-technology behavior. Use the Android platform recognizer behind a fakeable boundary; do not add direct audio upload or provider-specific transcription.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution verify Plans 012, 013 and 015 are DONE and Plan 014 is settled as DONE or BLOCKED with evidence. Record `PLAN_BASELINE=$(git rev-parse HEAD)`, inventory predecessor manifest/chat changes, and compare later work to that commit:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 016 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  app/src/main/AndroidManifest.xml app/src/main/java/com/ayagmar/pimobile/ui/chat \
  app/src/main/java/com/ayagmar/pimobile/chat app/build.gradle.kts \
  app/src/test app/src/androidTest docs plans
```

Re-read current `PromptControls`, `PromptInputRow`, draft restoration and dispatch tests before editing.

## Current state at the authoring baseline

- `ChatComposer.kt` owns an externally hoisted `String` draft, image picker, active-run delivery selector and send controls.
- `ChatViewModel` owns normal prompt, Follow up, Steer, retry restrictions, dispatch state and SavedStateHandle draft behavior.
- The authoring-baseline manifest has no microphone permission. Plan 014 will have added service/notification declarations that must be preserved.
- No speech recognizer abstraction or microphone lifecycle exists.

## Decisions and invariants

- Use `android.speech.SpeechRecognizer`/`RecognizerIntent` only through an injected interface. Verify API 37 lifecycle/main-thread/manifest requirements from official Android docs before coding. Do not add a speech SDK dependency without a separate approved decision.
- `RECORD_AUDIO` is requested just in time after an explanatory action. Denial, “don’t ask again”, recognizer unavailable and service/network failure have distinct recovery states. Never request at startup.
- The platform recognition service may transmit audio off-device. Disclose this before first use; do not claim local/offline processing. `EXTRA_PREFER_OFFLINE` may be offered only as a preference and never represented as a guarantee.
- Create/start/stop/cancel/destroy occur on the required thread and are tied to composition/lifecycle. Only one recognition generation is active. Late/duplicate callbacks after cancel/dispose/retarget are ignored.
- Partial text is transient preview and is not committed to SavedStateHandle. One final result is inserted exactly once at the user's current selection. If retaining selection would require a deliberate migration from `String` to `TextFieldValue`, perform that migration with characterization tests across send, restoration, command palette and active-run modes; do not silently append or overwrite. Existing draft is never lost.
- Dictation never sends. The existing explicit Send action remains the only dispatch path and all current streaming/steer/follow-up/lock guards remain authoritative.
- No recognized draft/audio/error payload enters logs, analytics or system notification content.

## Scope

**In scope**:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatComposer.kt`
- focused dictation adapter/state under `app/src/main/java/com/ayagmar/pimobile/chat/**` or `ui/chat/**`
- narrowly related Chat UI/ViewModel/draft model changes required for selection-safe insertion
- focused unit/Compose/source tests with fake recognizer/permission/lifecycle
- `README.md`, `docs/testing.md`, `docs/release.md`, privacy/onboarding documentation
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- Realtime voice conversation, audio streaming to Pi, custom PCM recording or provider transcription APIs.
- Automatic sending, background recording, persistent audio or transcript logging.
- Chat timeline redesign, RPC changes or direct device testing.

## Steps

### Step 1: Verify platform contract and add a fakeable recognizer

Read official API 37 `SpeechRecognizer`, `RecognizerIntent`, permission and package-visibility guidance. Record whether recognition is on-device-capable, network-dependent or service-provided without claiming availability. Add a main-thread adapter interface for availability, start, partial/final, end-of-speech, cancel, destroy and typed errors. Bound result count/length before exposing it to UI.

Add fake tests for unavailable service, busy/duplicate start, permission denied, start failure, partial/final/empty result, no-match, timeout/network/service error, cancel, destroy and late/duplicate callbacks. Error text must not echo recognized content.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: adapter/state tests pass with no real microphone/service/network and no content in errors/log assertions.

### Step 2: Add just-in-time permission and lifecycle-safe UI

Add only required manifest permission/query declarations. Provide first-use disclosure, permission request and denied/permanently-denied recovery. Add a 48dp mic/cancel action, state description, partial preview, audio/listening indication and accessible live-region status. Dispose/cancel correctly on navigation, lifecycle stop, permission loss and active generation replacement.

Disable dictation only for conditions that truly conflict (for example dispatch in progress); speech can prepare a draft during a run but must preserve the active Follow up/Steer selector and never dispatch.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: fake permission/lifecycle tests cover grant/deny/permanent deny, background/dispose, rotation state contract and exactly one recognizer; source compiles/lints cleanly.

### Step 3: Insert final text without losing selection or draft

Characterize current draft/send/restoration/command tests first. Commit partial results only to transient UI. Insert one final result at the active selection with predictable whitespace and selection-after-insert. Preserve existing text, images, command-menu behavior and SaveStateHandle semantics. If migrating to `TextFieldValue`, keep persisted state text-only and restore a bounded valid selection; do not persist speech partials.

Test insertion at start/middle/end, selected-range replacement, multiline text, blank/oversized final, duplicate final, user edits while recognizing, cancellation and process restoration. Send tests must prove dictation cannot invoke prompt/follow-up/steer.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: insertion/restoration/send-regression tests pass and final callbacks commit exactly once.

### Step 4: Accessibility, privacy and documentation

Audit keyboard focus, IME, font scale, TalkBack labels/order, live-region verbosity, permission/error copy, RTL and compact/expanded composer layout. Document platform-service/off-device possibility, no Pi Mobile audio persistence, offline preference limitations and operator-owned device checks. Do not run microphone/device acceptance without `debug mode`.

**Verify**:

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git status --short
```

Expected: all commands pass; a `git diff --stat <recorded Plan 016 baseline>..HEAD` over the scope shows only in-scope files.

## Done criteria

- [ ] Official-contract notes and tests prove a fakeable, bounded, lifecycle-safe recognizer with exactly one active generation.
- [ ] Permission/UI tests prove just-in-time disclosure, denial recovery and no startup/background recording.
- [ ] Draft tests prove selection-safe exactly-once final insertion, partial non-persistence and no draft/image loss.
- [ ] Send tests prove recognition never invokes prompt, Follow up or Steer and existing guards remain unchanged.
- [ ] Privacy tests/docs prove Pi Mobile persists no audio and does not claim platform recognition is offline.
- [ ] Complete non-device commands exit 0; device evidence remains pending.

## STOP conditions

Stop if:

- The selected API requires direct Pi/provider credentials, custom audio persistence or an unapproved SDK.
- Selection-safe insertion cannot be added without losing current SavedStateHandle/send behavior.
- Recognition would auto-send, run in background or bypass current delivery guards.
- Platform behavior cannot be isolated behind controlled fakes.
- A verification command fails twice after a reasonable correction.
