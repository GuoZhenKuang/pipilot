# Testing Pi Mobile

> **Device boundary:** The emulator/device, ADB, installation, screenshot, manual acceptance, connected-test, and benchmark commands below are operator-owned. Do not run them until the operator explicitly says `debug mode`. Ordinary development uses the non-device gates at the end of this document.

## Running on Emulator

### 1. Start an Emulator

**Option A: Via Android Studio**
- Open Android Studio
- Tools → Device Manager → Create Device
- Pick a phone (Pixel 7 recommended)
- Download a stable Android API 37 system image
- Click the play button to launch

**Option B: Via Command Line**

List available emulators:
```bash
$ANDROID_HOME/emulator/emulator -list-avds
```

Start one:
```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_7_API_37 -netdelay none -netspeed full
```

### 2. Build and Install

Build the debug APK:
```bash
./gradlew :app:assembleDebug
```

Install on the running emulator:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or build + install in one go:
```bash
./gradlew :app:installDebug
```

### 3. Launch the App

The app should appear in the app drawer. Or launch via adb:
```bash
adb shell am start -n com.ayagmar.pimobile/.MainActivity
```

### 4. View Logs

Watch logs in real-time:
```bash
# All app logs
adb logcat | grep "PiMobile"

# Performance metrics
adb logcat | grep "PerfMetrics"

# Frame jank detection
adb logcat | grep "FrameMetrics"

# Everything
adb logcat -s PiMobile:D PerfMetrics:D FrameMetrics:D
```

## Testing with the Bridge

Since the app needs the bridge to function:

### 1. Start the Bridge on Your Laptop

```bash
cd bridge
pnpm install  # if not done
pnpm start
```

Confirm the configured host and the sanitized listening-port log before connecting.

### 2. Configure the App

In the emulator app:
1. Open the left drawer → **Hosts** tab → **Add host**
2. Enter your laptop's Tailscale IP or MagicDNS hostname (`*.ts.net`)
3. Port: `8787` (or whatever the bridge uses)
4. Token: whatever you set in `bridge/.env` as `BRIDGE_AUTH_TOKEN`

### 3. Test the Connection

If the app shows "Connected" and lists your sessions, it's working.

If not, check:
- Is Tailscale running on both laptop and emulator host?
- Can the emulator reach your laptop? Test with: `adb shell ping 100.x.x.x`
- Is the bridge actually running? If the optional health endpoint is enabled and allowed on that interface, check it without including credentials.

## Common Issues

### "No hosts configured" shows immediately

Normal on first launch. Open the left drawer and go to the **Hosts** tab to add one.

### "Connection failed"

- Check Tailscale is running on both ends
- Verify the IP address is correct
- Make sure the bridge is listening on a reachable host (`BRIDGE_HOST`, e.g. Tailscale IP or 0.0.0.0)
- Check `bridge/.env` has correct `BRIDGE_PORT` and `BRIDGE_AUTH_TOKEN`

### Sessions don't appear

- Check `~/.pi/agent/sessions/` exists on your laptop
- The bridge needs read access to that directory
- Check bridge logs for errors

### App crashes on resume

- Capture a sanitized stack trace without tokens, session content, or private paths.
- Record session entry count and whether the failure occurred during bootstrap, first frame, or tree refresh.
- The app uses bounded initial history and generation-gated loads; treat an OOM or stale-frame failure as a regression rather than expected large-session behavior.

## Quick Development Cycle

For rapid iteration:

```bash
# Terminal 1: Keep logs open
adb logcat | grep -E "PiMobile|PerfMetrics|FrameMetrics"

# Terminal 2: Build and install after changes
./gradlew :app:installDebug

# The app stays open, just reinstalls
```

Or use Android Studio's "Apply Changes" for hot reload of Compose previews.

## Running Tests

Use JDK 25 for Gradle and compilation, a JDK 21 toolchain for stable detekt, Android SDK platform 37.0/build-tools 37.0.0, Node 24 LTS+, and pnpm 10.

Complete non-device gate:

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

Focused Chat Experience v2 unit tests:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.chat.*' --tests 'com.ayagmar.pimobile.ui.chat.*'
```

Compile connected tests without launching an emulator/device:

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

Device acceptance for Plans 006–011 is **PENDING — operator-owned**. Plan 012 device acceptance is also **PENDING — operator-owned**. Do not run an emulator, connected tests, `adb`, install an APK, benchmark, capture screenshots, or claim manual acceptance until the operator explicitly enables `debug mode`.

Plan 012 device evidence to collect when enabled:

- cold custom-scheme link with configured exact endpoint and verified share-origin alias;
- warm/repeated intents, two rapid links, rotation/process recreation, cancellation, and stale-generation suppression;
- unmatched and ambiguous authorities, missing/invalid token, authenticated hello alias change, revoked/missing/deleted session, corrupt state, and lock denial;
- create/repeat/copy/share/revoke/regenerate from Sessions; configured-origin browser landing and generic metadata-free page;
- record APK/build identity, bridge version/configuration without secrets, sanitized outcome, and timestamp. Do not capture or paste links containing private authorities into public reports.

Follow [`revival-acceptance.md`](revival-acceptance.md) and [`perf-baseline.md`](perf-baseline.md) when enabled.
