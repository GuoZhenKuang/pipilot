# Release verification

Pi Mobile does not store signing credentials in the repository. The checked-in release task produces an unsigned/default release artifact for static verification.

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

Expected artifacts:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

Before distribution:

1. Review `docs/dependency-matrix.md` and Pi compatibility policy.
2. Complete `docs/revival-acceptance.md` on an operator-owned device.
3. Sign outside the repository using protected operator/CI key material.
4. Verify the signed APK and preserve checksums, version, commit, and acceptance evidence.
5. Never commit keystores, passwords, service credentials, or generated signing configuration.
