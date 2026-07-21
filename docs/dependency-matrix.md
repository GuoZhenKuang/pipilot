# Dependency and toolchain matrix

Validated on 2026-07-17. Stable releases were required; previews were excluded.

## Plan 011 selected matrix

The operator approved the newest mutually supported stable/LTS platform matrix. Gradle and production/test compilation use JDK/JVM 25. Stable detekt 1.23.8 cannot run on JDK 25, so the quality gate invokes its CLI with an isolated JDK 21 toolchain and analysis target 21. This preserves detekt without adopting its alpha 2.x line or weakening the gate.

| Component | Previous | Selected | Required JDK | Required Gradle | Required Android SDK | Rationale |
|---|---:|---:|---:|---:|---:|---|
| Android Gradle Plugin | 8.13.2 | 9.1.1 | 17 minimum; 25 selected runtime | 9.3.1 | API 37 maximum | API 37 requires AGP 9.1.1 or newer. AGP 9.1.1 is the first stable API 37-compatible line and remains within the Kotlin 2.4 compatibility family; newer AGP 9.3 was rejected because Kotlin 2.4.10 does not document that line as fully supported. |
| Gradle | 8.14.5 | 9.3.1 | 17 minimum; supports running on 25 | — | — | Required by AGP 9.1.1. Gradle 9.1+ officially supports Java 25 as both daemon runtime and toolchain. |
| JDK/JVM target | 21 | 25 | 25 | 9.1+ | — | Plan 011 requires the Gradle runtime, Java toolchains, Kotlin bytecode targets, CI, and static analysis to use Java 25. |
| Kotlin / KGP | 2.2.21 | 2.4.10 | 25 selected | 7.6.3–9.5.0 fully supported | AGP 8.5.2–9.1.x family | Kotlin 2.3 first added Java 25 bytecode; stable Kotlin 2.4.10 supports it and includes the matching Compose and serialization compiler plugins. AGP built-in Kotlin requires an explicit root KGP classpath override above AGP's embedded KGP 2.2.10. |
| Kotlin Android integration | `org.jetbrains.kotlin.android` | AGP built-in Kotlin | 25 selected | 9.3.1 | API 37 | AGP 9 enables built-in Kotlin. Android modules must remove `org.jetbrains.kotlin.android`; JVM-only core modules retain `org.jetbrains.kotlin.jvm`. |
| Compose compiler | Kotlin plugin 2.2.21 | Kotlin plugin 2.4.10 | 25 selected | Kotlin-compatible | API 37 | `org.jetbrains.kotlin.plugin.compose` must match Kotlin. |
| compileSdk / targetSdk | 36 / 36 | 37 / 37 | — | AGP 9.1.1+ | Android 17/API 37 | Android 17 is stable and API level 37. |
| Android SDK Build Tools | 36.0.0 | 37.0.0 | — | AGP 9.1.1+ | API 37.0 | Stable packages `platforms;android-37.0` and `build-tools;37.0.0` were resolved and installed from the official SDK repository. |
| Compose BOM | 2026.06.00 | 2026.06.00 | — | — | — | Already the current stable BOM. |
| AndroidX Core KTX | 1.18.0 | 1.19.0 | — | — | API 37 compatible | Current stable AndroidX Core line. |
| AndroidX Activity Compose | 1.12.4 | 1.13.0 | — | — | API 37 compatible | Current stable Activity line, including current predictive Back integration. |
| AndroidX Lifecycle | 2.10.0 | 2.11.0 | — | — | API 37 compatible | Current stable Lifecycle line. |
| Navigation Compose | 2.9.8 | 2.9.8 | — | — | API 37 compatible | Current stable line; preview Navigation artifacts remain rejected. |
| AndroidX Benchmark Macro | 1.2.4 | 1.4.1 | — | AGP 9 compatible | API 37 compatible | Current stable macrobenchmark line. Connected benchmarks remain operator-owned. |
| AndroidX Test JUnit / Espresso / UIAutomator | 1.1.5 / 3.5.1 / 2.2.0 | 1.3.0 / 3.7.0 / 2.4.0 | — | — | API 37 compatible | Current stable AndroidX Test releases. |
| Kotlinx Kover | 0.9.8 | 0.9.8 | 25 selected | Gradle 9 fixes present since 0.9.1 | — | Current stable Kover documentation uses 0.9.8. |
| ktlint Gradle plugin | 12.1.1 | 14.2.0 candidate | 25 selected | Gradle 9 support added in 13.1.0 | — | Current stable plugin; focused verification would still be required after the blocker clears. |
| detekt | 1.23.8 Gradle plugin | 1.23.8 CLI | 21 isolated analysis launcher | Gradle 9 invokes the CLI | — | Stable 1.23.8 cannot parse Java 25 runtime version strings. It runs as a Gradle `JavaExec` quality task on the JDK 21 toolchain; detekt 2.x alpha remains rejected. |
| Kotlin serialization JSON | 1.11.0 | 1.11.0 | 25 selected | Kotlin-compatible | — | Current stable runtime; compiler plugin would match Kotlin 2.4.10. |
| Kotlin coroutines | 1.11.0 | 1.11.0 | 25 selected | Kotlin-compatible | — | Current stable runtime. |
| OkHttp | 5.4.0 | 5.4.0 | 25 selected | — | — | Current stable release. |
| Coil Compose | 2.7.0 | 2.7.0 pending migration review | 25 selected | — | — | Retained until a Coil 3 source/API migration can be characterized; no preview is needed for API 37. |
| Java Diff Utils | 4.17 | 4.17 | 25 selected | — | — | Current stable release. |
| Prism4j | 2.0.0 | 2.0.0 | 25 selected | — | — | No migration requirement identified. |
| Google Code Scanner | 16.1.0 | 16.1.0 | — | — | API 37 compatible | Current permissionless scanner contract is retained. |
| Node.js | 22 LTS in CI | 22 LTS in CI | — | — | — | Supported bridge baseline remains Node 22+; local Node 24 passed the baseline. |
| pnpm | 10.33.0 | 10.33.0 | — | — | — | Matches `packageManager`, lockfile, and CI. |
| Bridge runtime packages | current lockfile | current stable lockfile | — | — | — | Frozen install, ESLint, typecheck, tests, and production audit remain mandatory. Protocol/process changes are out of scope. |
| Pi | 0.80.6 minimum | 0.80.6 minimum | — | — | — | RPC compatibility baseline remains unchanged. |

## Tool-specific compatibility decision

The first migration attempt confirmed that detekt 1.23.8 crashes under a JDK 25 Gradle daemon because its embedded Kotlin/IntelliJ runtime cannot parse the `25.0.1` Java version string. The operator approved a stable-only tool isolation decision:

- Gradle, Android/JVM compilers, unit tests, and application bytecode use JDK/JVM 25.
- The `detekt` Gradle gate remains present but invokes stable `detekt-cli:1.23.8` with Gradle's JDK 21 toolchain and `jvm-target=21`.
- HTML, XML, and SARIF reports remain generated per module.
- detekt 2.x alpha is not used; when detekt 2 reaches stable JDK 25 support, the isolated launcher can be removed.
- ktlint uses the current Gradle 9-compatible plugin while pinning the established ktlint 1.0.1 formatting engine to avoid an unrelated repository-wide style rewrite.

## Preserved compatibility and security notes

- Existing `EncryptedSharedPreferences` tokens cannot be decrypted after removing the deprecated library. The approved migration clears only the old encrypted token preference file, preserves host profiles, and shows a restrained re-entry notice. No plaintext conversion is attempted.
- New token values remain encrypted with a non-exportable Android Keystore AES key and AES-GCM. Corrupted ciphertext is removed and treated as missing.
- App backup remains disabled so encrypted token payloads are not restored without their device-bound Keystore key.
- Release minification remains disabled because enabling it without device release smoke coverage would broaden the migration.
- Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd remains unchanged.

## Authoritative sources

- AGP releases and API/Gradle compatibility: https://developer.android.com/build/releases/about-agp
- AGP 9.1 release notes: https://developer.android.com/build/releases/agp-9-1-0-release-notes
- AGP 9.3 release notes (considered and rejected): https://developer.android.com/build/releases/agp-9-3-0-release-notes
- AGP built-in Kotlin migration: https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9 built-in Kotlin/KGP override notes: https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Gradle Java compatibility: https://docs.gradle.org/current/userguide/compatibility.html
- Gradle 9.1 Java 25 support: https://docs.gradle.org/9.1.0/release-notes.html
- Kotlin/Gradle/AGP compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Kotlin Java 25 bytecode support: https://kotlinlang.org/docs/whatsnew23.html
- Compose compiler migration: https://kotlinlang.org/docs/compose-compiler-migration-guide.html
- Compose compiler setup: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- Android 17/API 37 setup: https://developer.android.com/about/versions/17/setup-sdk
- Android 17 stable announcement: https://developer.android.com/blog/posts/android-17-is-here
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- AndroidX stable versions: https://developer.android.com/jetpack/androidx/versions
- AndroidX Benchmark releases: https://developer.android.com/jetpack/androidx/releases/benchmark
- AndroidX Test releases: https://developer.android.com/jetpack/androidx/releases/test
- detekt compatibility table: https://detekt.dev/docs/introduction/compatibility/
- detekt releases: https://github.com/detekt/detekt/releases
- ktlint Gradle plugin releases: https://plugins.gradle.org/plugin/org.jlleitschuh.gradle.ktlint
- Kover Gradle plugin: https://kotlin.github.io/kotlinx-kover/gradle-plugin/
- OkHttp releases: https://square.github.io/okhttp/
- Kotlinx serialization: https://github.com/Kotlin/kotlinx.serialization/releases
- Kotlinx coroutines: https://github.com/Kotlin/kotlinx.coroutines/releases
