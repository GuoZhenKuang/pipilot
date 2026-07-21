# Revival acceptance checklist

All device-only results are **PENDING — operator-owned**. Do not replace this status without recording real evidence.

## Prerequisites

- JDK 25, a JDK 21 toolchain for stable detekt, Android SDK/platform 37.0 and build-tools 37.0.0, adb, Node 24 LTS+, pnpm 10, and Pi 0.80.6+
- A bridge configured with a fresh `BRIDGE_AUTH_TOKEN` and reachable Tailnet address
- An emulator/device with no private data in screenshots or logs

Build artifacts:

```bash
./gradlew :app:assembleDebug :app:assembleRelease
ls -l app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Evidence header:

```text
Device model:
Android/API:
App commit:
Pi version:
Node/pnpm version:
Bridge commit/config (no secrets):
Date/operator:
```

## Installation and onboarding

1. `adb uninstall com.ayagmar.pimobile || true`
2. `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Launch: `adb shell am start -n com.ayagmar.pimobile/.MainActivity`
4. Confirm first launch shows **Connect your computer**, not an empty CRUD list.
5. Try an unreachable host; expect actionable network recovery.
6. Try a bad token; expect authentication recovery and no token text on screen/logcat.
7. Save and test a valid bridge; expect Sessions to open.
8. Restart the app; expect host metadata retained and stored token never rendered.

Result: **PENDING — operator-owned**
Evidence/notes: ______________________________

## Sessions and chat

For each action, verify one resulting item/state and no duplicate timeline message:

- list and refresh sessions; empty state offers a primary action
- create and resume a session
- rename, fork, tree jump, import JSONL, export HTML, and compact
- send text and image prompts
- observe text, thinking, tool execution, retry/compaction, and settled lifecycle
- abort, steer, and follow-up
- respond to extension select/confirm/input/editor; inspect widget/status updates
- disconnect/reconnect; ordinary reconnect must preserve timeline without full-history duplication
- edit the active session externally; foreground safety sync must detect it within 60 seconds
- navigate a branch without appending; expect exactly one safe rebuild

Result: **PENDING — operator-owned**
Evidence/notes: ______________________________

## Multiple hosts and recovery

1. Add a second computer and switch hosts.
2. Hold the same cwd lock from another client; expect lock guidance, not a raw exception.
3. Stop the bridge and restart it during chat; expect reconnect/resync.
4. Remove model credentials; expect Pi readiness guidance.
5. Corrupt only a test token ciphertext, relaunch, and verify the token is treated as missing without a crash.

Result: **PENDING — operator-owned**
Evidence/notes: ______________________________

## Accessibility and lifecycle

1. Verify all primary controls have at least 48dp targets and meaningful TalkBack labels.
2. Test TalkBack order through onboarding, Sessions, and composer.
3. Set font scale to 1.3–1.5x; verify portrait and landscape without clipped primary actions.
4. Test keyboard Next/Done behavior and token masking.
5. Background chat for over 60 seconds; confirm safety polling pauses, then resumes on foreground.
6. Rotate and background/restore during idle and streaming; verify current session restoration.

Result: **PENDING — operator-owned**
Evidence/notes: ______________________________

## Release APK

The repository does not contain signing credentials. Install a safely signed operator copy of `app/build/outputs/apk/release/app-release-unsigned.apk`, launch it, connect, resume, prompt, and reconnect. Record signing method without recording key material.

Result: **PENDING — operator-owned**
Evidence/notes: ______________________________

## Non-device gate

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

Expected: every command exits 0; APK paths above exist; production audit has no high vulnerabilities.

Recorded non-device result (2026-07-13): **PASS**

- Android clean/static/unit/lint/debug/release gate: exit 0
- Bridge frozen install/check/production audit: exit 0; no known vulnerabilities
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Connected/manual sections above: **PENDING — operator-owned**
