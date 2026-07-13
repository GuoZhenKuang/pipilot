# Dependency and toolchain matrix

Validated on 2026-07-13. Targets are stable releases; previews were excluded.

| Component | Previous | Target | Rationale |
|---|---:|---:|---|
| Android Gradle Plugin | 8.5.2 | 8.13.2 | Latest AGP 8 stable line with Android API 36 support. AGP 9 changes the Kotlin integration model and is deferred rather than combining that migration with the revival feature set. |
| Gradle | 8.7 | 8.14.5 | Latest Gradle 8 stable release; retains the existing Gradle 8 build model and passes AGP 8.13 checks. |
| JDK/JVM target | 21 | 21 | Existing project baseline; supported by Gradle 8.14.5 and AGP 8.13. |
| Kotlin | 1.9.24 | 2.2.21 | Stable Kotlin 2 release compatible with AGP 8.13 and the Compose compiler Gradle plugin. Kotlin 2.4 is not selected because the current Android plugin compatibility path is AGP 9. |
| Compose compiler | 1.5.14 | Kotlin plugin 2.2.21 | Kotlin 2 uses `org.jetbrains.kotlin.plugin.compose` at the Kotlin version. |
| Compose BOM | 2024.06.00 | 2026.06.00 | Current stable Compose BOM at execution time. |
| Kotlinx Kover | 0.8.3 | 0.9.8 | Current stable plugin; fixes clean-test instrumentation compatibility with Kotlin 2.2/Gradle 8.14.5. |
| AndroidX Navigation Compose | 2.7.7 | 2.9.8 | Current stable Navigation release. |
| compileSdk / targetSdk | 34 / 34 | 36 / 36 | Android 16 stable SDK and current target baseline. |
| AndroidX security-crypto | 1.1.0-alpha06 | removed | The package is deprecated. Tokens now use platform Android Keystore AES-256-GCM. |
| Node.js | 22 in CI | 22 LTS in CI | Bridge CI remains on a supported LTS runtime; local Node 24 also passes bridge checks. |
| pnpm | 9 in CI | 10.33.0 | Matches the lockfile/tool used for final frozen installs. |
| Pi | implicit | 0.80.6 minimum | Required compatibility baseline for lifecycle, entries, tree, and session-format behavior. |

## Compatibility and migration notes

- Existing `EncryptedSharedPreferences` tokens cannot be decrypted after removing the deprecated library. The approved migration clears only the old encrypted token preference file, preserves host profiles, and shows a restrained re-entry notice. No plaintext conversion is attempted.
- New token values are encrypted with a non-exportable Android Keystore AES key and AES-GCM. Corrupted ciphertext is removed and treated as missing.
- App backup is disabled so encrypted token payloads are not restored without their device-bound Keystore key.
- Release minification remains disabled because enabling it without device release smoke coverage would broaden this migration.

## Authoritative sources

- AGP releases and compatibility: https://developer.android.com/build/releases/gradle-plugin
- AGP 8.13 notes: https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes
- Gradle 8.14.5 release: https://docs.gradle.org/8.14.5/release-notes.html
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Compose compiler migration: https://kotlinlang.org/docs/compose-compiler-migration-guide.html
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Kover plugin: https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover
- Navigation releases: https://developer.android.com/jetpack/androidx/releases/navigation
- Android 16 SDK setup: https://developer.android.com/about/versions/16/setup-sdk
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Deprecated EncryptedSharedPreferences: https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
