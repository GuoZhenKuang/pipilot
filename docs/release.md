# Release verification

Pi Mobile does not store signing credentials in the repository. The checked-in release task produces an unsigned/default release artifact for static verification.

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

Plan 012 release notes: share references are durable bridge-owned state, not authorization. Back up `BRIDGE_STATE_DIR`; state reset invalidates references. `BRIDGE_SHARE_ORIGIN` is optional and must be reviewed as a strict configured origin. Verified Android App Links are not claimed; custom scheme plus self-hosted landing are the supported paths. Device evidence remains **PENDING — operator-owned**.

Plan 013 release notes: the Sessions cockpit is cache-first across hosts with a maximum of two concurrent index refreshes. Saved pin/hidden state contains only local stable keys and density; deleted profiles clear their local scope, while unavailable saved sessions remain removable/retryable placeholders. Normal cards/search omit full cwd and session paths. Quick reply is text-only, uses existing controller locks, and never switches away from another active run or navigates after send unless explicitly requested. Device evidence remains **PENDING — operator-owned**.

Expected artifacts:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

Before distribution:

1. Review `docs/dependency-matrix.md` and Pi compatibility policy.
2. Complete `docs/revival-acceptance.md` on an operator-owned device.
3. Sign outside the repository using protected operator/CI key material.
4. Verify the signed APK and preserve checksums, version, commit, and acceptance evidence.
5. Never commit keystores, passwords, service credentials, or generated signing configuration.
