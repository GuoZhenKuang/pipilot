# Performance Baseline

This document defines performance budgets and the operator-owned measurement procedure for Pi Mobile. Deterministic non-device tests verify bounds, cache behavior, request counts, and cancellation; they are not substitutes for hardware timings.

All connected benchmarks, profiler sessions, installations, ADB commands, and manual measurements are **PENDING — operator-owned** until the operator explicitly says `debug mode`.

## Performance Budgets

### Latency Budgets

| Metric | Target | Max | Status |
|--------|--------|-----|--------|
| Cold app start to visible cached sessions | < 1.5s | < 2.5s | PENDING — operator-owned |
| Resume session to first rendered messages | < 1.0s | - | PENDING — operator-owned |
| Prompt send to first token (healthy LAN) | < 1.2s | - | PENDING — operator-owned |

### Rendering Budgets

| Metric | Target | Status |
|--------|--------|--------|
| Main thread frame time | < 16ms (60fps) | PENDING — operator-owned |
| No sustained jank (>5min streaming) | 0 critical drops | PENDING — operator-owned |
| Large tool output default-collapsed | > 400 chars | ✅ Implemented |

### Memory Budgets

| Metric | Target | Status |
|--------|--------|--------|
| Streaming buffer per message | < 50KB | ✅ Implemented |
| Tracked assistant message buffers | 8 | ✅ Implemented |
| Event buffer capacity (RPC / bridge) | 256 / 128 events | ✅ Implemented |

## Measurement Infrastructure

### PerformanceMetrics

Tracks key user journey timings:

- `startup_to_sessions`: App start to sessions list visible
- `resume_to_messages`: Session resume to chat history rendered
- `prompt_to_first_token`: Prompt send to first assistant token

Usage:
```kotlin
// Automatic tracking in MainActivity, SessionsViewModel, ChatViewModel
PerformanceMetrics.recordAppStart()
PerformanceMetrics.recordSessionsVisible()
PerformanceMetrics.recordResumeStart()
PerformanceMetrics.recordFirstMessagesRendered()
PerformanceMetrics.recordPromptSend()
PerformanceMetrics.recordFirstToken()
```

### FrameMetrics

Monitors UI rendering performance during streaming:

```kotlin
// Automatically tracks during streaming
StreamingFrameMetrics(isStreaming = isStreaming) { droppedFrame ->
    Log.w("Jank", "Dropped ${droppedFrame.expectedFrames} frames")
}
```

Jank severity levels:
- **Medium**: 33-50ms (1 dropped frame at 60fps)
- **High**: 50-100ms (2-3 dropped frames)
- **Critical**: >100ms (6+ dropped frames)

## Running Benchmarks

Do not run this section without explicit operator `debug mode`. Use a disposable API 37 device/emulator and record the device/API, app commit, compilation mode, iteration count, and raw benchmark artifact location.

### Startup Benchmark

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=top.guozk.pipilot.benchmark.StartupBenchmark
```

The benchmark source compares no compilation with a required baseline profile. The baseline-profile case is expected to remain pending until a profile is generated and deliberately adopted for the distribution model.

### Baseline Profile Scaffolding

The repository contains `BaselineProfileGenerator`, but profile generation/adoption is not a current non-device release gate. If public distribution makes it worthwhile, run the generator class on an operator-owned device:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=top.guozk.pipilot.benchmark.BaselineProfileGenerator
```

Review generated artifacts before copying any profile into `app/src/main`; do not claim profile benefit without an A/B startup benchmark.

## Profiling

### Memory Profiling

Monitor memory usage during long streaming sessions:

```kotlin
val memoryUsage = bufferManager.estimatedMemoryUsage()
Log.d("Memory", "Streaming buffers: ${memoryUsage} bytes")
```

### Backpressure Monitoring

Check for event backpressure:

```kotlin
val droppedCount = buffer.droppedCount()
if (buffer.isBackpressuring()) {
    Log.w("Backpressure", "Dropped $droppedCount events")
}
```

## Synchronization Payload Baseline

The synchronization counters are exposed as `SessionSyncMetrics`: `fullRebuilds`, `incrementalEntries`, and `safetyPolls`.

Reproduce payload counts without a device:

```bash
./gradlew :core-net:test --tests top.guozk.pipilot.corenet.PiRpcConnectionTest
./gradlew :app:testDebugUnitTest --tests top.guozk.pipilot.sessions.SessionEntryProjectionTest
```

For a session with 10,000 existing entries followed by 10 new entries:

| Scenario | Before | After |
|---|---:|---:|
| Initial open | 1 `get_messages` full-history response | 1 full `get_entries` response |
| Ordinary reconnect with no changes | 1 `get_messages` full-history response | 1 `get_entries` response containing 0 entries |
| Reconnect after 10 appended entries | 1 `get_messages` full-history response | 1 `get_entries` response containing 10 entries |
| Unknown cursor or branch movement | 1 full-history response | 1 failed/incompatible incremental response plus exactly 1 full rebuild |
| Foreground safety checks per minute | 15 at the former 4-second interval | 1 at the 60-second interval |
| Background/inactive safety checks | 15 per minute at the former interval | 0 |

These are protocol payload counts derived from deterministic fixtures, not device timing claims.

## Current Hardware Baseline

**PENDING — operator-owned.** No hardware timing, jank, or memory result has been recorded.

### Primary acceptance device: Android API 37

| Metric | Compilation: None | Compilation: Baseline Profile |
|--------|------------------|------------------------------|
| Cold startup | PENDING | PENDING |
| Resume to first new-session frame | PENDING | PENDING |
| Prompt to first token | PENDING | PENDING |
| Long-session tree open/refresh | PENDING | PENDING |

### Lower supported API compatibility device

| Metric | Result |
|--------|--------|
| Startup and reconnect smoke | PENDING |
| 10+ minute streaming/jank | PENDING |
| Rotation/background restore | PENDING |
| Memory growth after repeated session switches | PENDING |

## Optimization Checklist

- [x] Bounded event buffer (128 events)
- [x] Streaming text buffer limits (50KB per message)
- [x] Segment compaction for long streams
- [x] LRU eviction for old messages
- [x] Frame metrics tracking
- [x] Startup timing instrumentation
- [ ] Baseline profile generation — conditional on a distribution decision
- [ ] Release build optimization verification — operator-owned
- [ ] Stress test: 10+ minute streaming — operator-owned
- [ ] Memory-growth/leak verification — operator-owned

## Known Evidence Gaps

- No API 37 hardware timing has been recorded.
- No sustained-streaming jank or memory profile has been recorded.
- Baseline profile generation and A/B benefit remain unverified and are not current release claims.

## Tools

- Android Studio Profiler: CPU, Memory, Energy
- Macrobenchmark library: Startup metrics
- Logcat filtering: `tag:PerfMetrics|tag:FrameMetrics`
