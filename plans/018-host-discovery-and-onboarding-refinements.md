# Plan 018: Improve host discovery and pairing without weakening authentication

> Fresh-context executor: read this plan completely before editing. QR pairing already works and contains a credential. Discovery is an advisory endpoint hint only; it never carries a token, auto-connects, auto-saves or replaces Tailscale/explicit authentication.

## Status

- State: TODO
- Priority: P3
- Effort: M–L (approximately 1–2 focused engineering weeks)
- Depends on: Plans 012, 013, 015–017; Plan 014 must be settled (DONE or BLOCKED)
- Category: onboarding / connectivity / security

## Objective

Add bounded private pairing-payload paste/import and explicit opt-in foreground local-network discovery with full API 37 permission/lifecycle handling. Discovery may identify candidates but is unauthenticated and must never cause a bearer token to be sent to an unverified endpoint. Bind a candidate to a credential-bearing QR/paste endpoint or an already verified TLS/Tailscale profile before connection, preserve staged diagnostics, and clearly separate pairing credentials from credential-free session links.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution verify Plans 012, 013 and 015–017 are DONE and Plan 014 is settled as DONE or BLOCKED. Record `PLAN_BASELINE=$(git rev-parse HEAD)` and inventory predecessor host/bridge/manifest changes:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 018 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  app/src/main/java/com/ayagmar/pimobile/ui/hosts \
  app/src/main/java/com/ayagmar/pimobile/hosts \
  bridge/src/pair.ts bridge/src/pairing.ts bridge/src/config.ts bridge/src/server.ts \
  bridge/test app/build.gradle.kts app/src/main/AndroidManifest.xml docs plans
```

Re-read Plan 012 link parsing/share state, final host models, token migration and diagnostics before editing.

## Current state at the authoring baseline

- `docs/onboarding.md` uses `pnpm pair`, terminal QR and Tailscale; the QR contains the bridge token and must remain private.
- `HostsScreen` uses Google Code Scanner and feeds raw QR text to `parseHostPairingPayload`.
- `HostPairingPayload.kt` checks type/version, validates a `HostDraft`, and returns token-bearing draft state, but has no input-size limit or paste source.
- `bridge/src/pairing.ts` advertises host/port/`useTls=false`/token; it does not have local discovery or a stable bridge instance reference.
- `HostTokenStore` is Keystore-backed. Pair/import state holds plaintext only in transient draft memory before save.
- Android API 37 local-network discovery permissions/consent may differ from earlier Android releases and must be verified from official docs at execution.

## Decisions and invariants

- Pairing paste accepts only a bounded known `pi-mobile-host` payload. Unknown version is rejected; unknown fields in a known version are ignored only after required typed fields pass validation, preserving forward compatibility. Errors never echo raw input/token.
- Clipboard content is read only after an explicit user action, held transiently, never logged/analysed, and not displayed in full. After successful import, offer an explicit **Clear copied pairing code** action; do not silently overwrite unrelated clipboard content. Document Android clipboard exposure and encourage QR for shared devices.
- Session share URIs from Plan 012 are routed to the link coordinator, not treated as host pairing payloads. Generic pasted text gets a type-safe error.
- Add a persistent 16-byte unpadded-base64url bridge `instanceReference` through an explicit backward-compatible migration of the shared bridge-state version; preserve all existing share/workspace mappings and fail closed on corruption. It is non-secret and intended for pairing/discovery deduplication; it is not the auth token and grants no access. Host profile migration makes this field optional for existing/manual profiles and fills it only after pairing review or authenticated handshake.
- Discovery is disabled by default and enabled only by explicit `BRIDGE_ENABLE_DISCOVERY=true`. Do not advertise when bound only to loopback or when no reachable local interface/port can be represented.
- Discovery advertises only version, instance reference, port, direct bridge TLS capability and bounded capability flags. Default instance name is generic/user-configured; do not expose OS username, full hostname, cwd/session data or token in TXT/service names. `instanceReference` is spoofable public metadata and is only for deduplication, never server authentication.
- Android discovery runs only while a user-visible screen/action is active, has a timeout/cancel action, releases NSD callbacks on stop/background and never auto-connects. Before any bearer token is transmitted, the endpoint must be bound by one of: (a) a reviewed QR/paste payload whose host and instance reference match; (b) an existing configured profile using its already verified endpoint; or (c) standard-valid WSS identity for the exact configured hostname. Discovery alone cannot enable Test/Save with a token. A mismatch fails before connecting.
- mDNS is local-link only and does not traverse Tailscale/NAT reliably. Tailscale MagicDNS/QR/manual entry remain supported remote paths.

## Dependencies and decision gate

Before discovery implementation, verify from official sources:

- API 37 Android NSD/local-network permissions, user consent and lifecycle;
- Node 24-compatible mDNS/DNS-SD implementation and maintenance/security status;
- IPv4/IPv6, interface selection, service collision and cleanup behavior;
- whether a candidate can be cryptographically/operationally bound to the reviewed QR/profile/TLS endpoint before sending a bearer token;
- whether a new production dependency is required and passes repository audit policy.

Outcome mapping:

- **GO**: stable mutually supported approach exists; record exact versions/permissions and implement Steps 2–4.
- **MODIFY**: official platform permission or bridge transport constraints require a narrower explicit discovery contract; update this plan, review it, then continue.
- **NO-GO**: no stable/auditable implementation exists; mark Plan 018 BLOCKED after Step 1 with evidence. Do not mark DONE while discovery remains unimplemented and do not substitute network scanning.

## Scope

**In scope**:

- `app/src/main/java/com/ayagmar/pimobile/ui/hosts/**`
- `app/src/main/java/com/ayagmar/pimobile/hosts/**`
- `bridge/src/pair.ts`, `pairing.ts`, `config.ts`, `server.ts`, Plan 012 state module and a focused discovery module
- focused Android/bridge tests with fake clipboard/NSD/advertiser/network interfaces
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` and bridge package/lock only when justified by the recorded GO decision
- `README.md`, `docs/onboarding.md`, `docs/bridge-protocol.md`, `docs/testing.md`, `docs/release.md`, dependency/ADR docs
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- NAT traversal, relay, SSH bootstrap, P2P transport, network scanning or removal of Tailscale.
- Tokenless auth, auto-approval/save/connect, public bridge exposure or token advertisement.
- Keystore redesign, bridge lock/process architecture changes or device execution without `debug mode`.

## Steps

### Step 1: Add safe pairing/share paste dispatch

Factor a bounded pure input classifier/parser used by scan and paste. Set a conservative byte/character bound before JSON/URI parsing. Route known Plan 012 session URIs to the link coordinator; route known pairing type/version to the existing reviewed Host editor; reject everything else safely. Keep token only in transient draft and existing secure-save path.

Add explicit paste action, preview only non-secret host/name/port/TLS/source fields, and optional conditional clipboard clearing. Test valid QR/paste equivalence, malformed/oversized JSON, wrong type/version, unknown fields, invalid host/port/TLS/token, session link dispatch, sensitive clipboard behavior and error redaction.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: all parser/UI tests pass; token/raw clipboard never appears in rendered error/log assertions.

### Step 2: Complete discovery GO/MODIFY/NO-GO research

Record official source links, selected Android permission/consent flow, Node implementation/version, dependency audit, service schema, timeout/interface policy and threat model. Run non-mutating package/advisory discovery only; do not add a dependency until GO is recorded.

**Verify**:

```bash
rg -n 'GO|MODIFY|NO-GO|Android NSD|API 37|local network|mDNS|DNS-SD' docs plans/018-host-discovery-and-onboarding-refinements.md
```

Expected: one outcome with evidence. NO-GO marks the plan BLOCKED; MODIFY requires plan re-review before code.

### Step 3: Add opt-in, non-authorizing bridge advertisement

For GO only, generate/load stable `instanceReference`, parse `BRIDGE_ENABLE_DISCOVERY=false` by default, advertise only from reachable non-loopback direct bridge interfaces, and publish the bounded versioned schema. Handle interface changes, duplicate names, start/stop, server shutdown, advertiser failure and IPv4/IPv6 deterministically. Advertisement failure must not prevent bridge/WebSocket startup; expose a sanitized warning/diagnostic.

Use injected advertiser/network-interface dependencies in tests. No unit test opens real multicast/network or writes real user state.

**Verify**:

```bash
(cd bridge && pnpm run check && pnpm audit --prod)
```

Expected: config/schema/lifecycle/interface/collision/failure tests pass and packet/TXT fixtures contain no token/path/cwd/hostname/session data.

### Step 4: Add foreground Android discovery and host review

Implement a user-initiated discovery action with official API 37 permission/consent flow, timeout, cancel, loading/empty/error states and deterministic service deduplication by instance reference. Validate resolved host/IP/port/TLS before presenting it; do not connect automatically. A candidate offers **Pair with QR/paste** or matching to an existing verified profile. Do not expose token entry/Test/Save for an unbound candidate. Use the endpoint from the reviewed pairing/profile/TLS identity, not mutable mDNS data. Host/port/TLS/instance mismatch fails before a bearer token is sent.

After secure endpoint binding, preserve staged diagnostics: reachability, authentication, bridge compatibility and Pi readiness. Distinguish QR/paste/discovery/manual source without persisting token-bearing raw payload. An authenticated hello mismatch still fails closed and requires re-pairing.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: fake NSD/permission tests cover grant/deny, timeout/cancel, background cleanup, duplicates, IPv4/IPv6, identity mismatch and explicit review; no real LAN runs.

### Step 5: Document boundaries and run complete gates

Document credential-bearing QR/paste privacy, credential-free session links, local-only discovery, default-off bridge flag, permission UX, instance-reference reset/deduplication and Tailscale remote guidance. Add but do not run device/LAN acceptance steps.

**Verify**:

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
git status --short
```

Expected: every command passes and a `git diff --stat <recorded Plan 018 baseline>..HEAD` over the scope shows only in-scope files.

## Done criteria

- [ ] Parser/UI tests prove bounded QR/paste equivalence, type-safe session-link dispatch, transient token handling and secret-safe errors.
- [ ] Discovery decision is GO and records official API 37/Node/dependency evidence; NO-GO leaves plan BLOCKED, not DONE.
- [ ] Bridge tests prove default-off, reachable-interface-only, non-authorizing bounded advertisements and cleanup/failure behavior.
- [ ] Android tests prove explicit permission/action/review, timeout/cancel/background cleanup, deduplication, pre-token secure endpoint binding and identity mismatch handling.
- [ ] Existing Keystore/diagnostics/pairing tests remain green and no token enters advertisement/share link/log.
- [ ] Complete non-device commands exit 0; device/LAN evidence remains pending.

## STOP conditions

Stop if:

- Discovery cannot meet GO requirements or would use network scanning, advertise a token, auto-connect/save, or expose host/user/session metadata.
- A discovered endpoint could receive any bearer token before QR/profile/TLS endpoint binding; post-auth instance checking alone is insufficient.
- A new dependency is unstable, incompatible or fails production audit policy.
- API 37 permission/lifecycle cannot be represented with controlled fakes.
- A verification command fails twice after a reasonable correction.
