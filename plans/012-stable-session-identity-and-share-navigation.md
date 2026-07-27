# Plan 012: Add stable session identity and authenticated share navigation

> Fresh-context executor: read this plan completely before editing. Preserve Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd. A URL may contain a purpose-built opaque share reference and non-secret bridge authority only. Never put a Pi `sessionId`, token, authorization header, cwd, absolute session path, transcript content, local host-profile ID, or raw client/lock ID into a URL or user-facing log.

## Status

- State: IN PROGRESS
- Priority: P0
- Effort: L–XL (approximately 10–15 focused engineering days; device acceptance is separate and operator-owned)
- Depends on: Plan 011 non-device gates; device acceptance from Plans 008–011 is not a prerequisite
- Category: product / identity / navigation / security

## Objective

Introduce two deliberately different identities:

1. an internal `SessionKey(hostProfileId, sessionId)` for authenticated/local state; and
2. an external `SharedSessionLocator(authority, shareReference, version)` that contains no raw Pi ID or credential.

Allow a recipient who already has authorized host access to open a shared session directly. The bridge must resolve the opaque reference only after authentication, and existing cwd/session control locks remain authoritative. Deliver stable/revocable share references, cold/warm Android routing, a normal self-hosted HTTP(S) landing URL when configured, custom-scheme fallback, safe failure/retry states, share/copy/revoke actions, durable reference storage and regression coverage.

## Baseline

- Written against: commit `d47ab00` on `master`.
- Drift check (run first):

```bash
git status --short --branch
git rev-parse --short HEAD
git diff --stat d47ab00..HEAD -- \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/com/ayagmar/pimobile/MainActivity.kt \
  app/src/main/java/com/ayagmar/pimobile/di \
  app/src/main/java/com/ayagmar/pimobile/hosts \
  app/src/main/java/com/ayagmar/pimobile/sessions \
  app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt \
  app/src/main/java/com/ayagmar/pimobile/ui/hosts \
  app/src/main/java/com/ayagmar/pimobile/ui/sessions \
  core-sessions bridge/src bridge/test app/src/test core-sessions/src/test docs README.md plans
```

If any in-scope source changed, re-read it and reconcile before editing. Ignore only the expected uncommitted plan-document changes. Stop if runtime drift cannot be reconciled without weakening authentication, lock ownership, process isolation or session safety.

## Current state

- `core-sessions/.../SessionIndexModels.kt:6-16` stores `SessionRecord.sessionPath` but no Pi session ID.
- `core-sessions/.../SessionIndexRepository.kt:191-238` merges records by `sessionPath`; a moved session is therefore treated as a different record.
- `bridge/src/session-indexer.ts:8-18` returns path, cwd, timestamps and previews but does not expose the documented session-header ID.
- Pi `docs/rpc.md` documents `get_state.data.sessionId`; Pi `docs/session-format.md` documents a session-header UUID-like `id` and optional `parentSession`.
- `SessionsViewModel.resumeSession()` already owns token retrieval, connection, switch/control acquisition and navigation. Do not create a second resume implementation.
- `BridgeSessionIndexRemoteDataSource` already performs authenticated, read-only bridge requests on host-scoped transports; it is the convention to match for share create/resolve/revoke requests.
- `HostProfile.id` is local-only. `HostProfile.endpoint` is `ws(s)://host:port/ws`; no verified share-origin alias is currently stored, so a reverse-proxy/public share origin would not match an existing profile.
- `MainActivity` has no `ACTION_VIEW` or `onNewIntent()` handling. `PiMobileApp` has no pending-link coordinator.
- The bridge has no persistent application state directory. `BridgeConfig` currently contains runtime/session/auth limits only.
- The bridge HTTP server serves `/health` and otherwise returns 404. It must not trust an inbound `Host` header when generating share links.

## Decisions and invariants

### Internal and external identity

- Pi `sessionId` is the canonical internal identity within one configured host. It may be stored in authenticated indexes/local app state but must not be rendered in ordinary UI, URLs or logs.
- Local pins/notifications later use `SessionKey(hostProfileId, sessionId)`. Local profile IDs are appropriate locally and survive endpoint edits; they are not used cross-device.
- External links use a bridge-generated `shareReference`, never `sessionId`. A reference is 16 cryptographically random bytes encoded as unpadded base64url (22 characters, `^[A-Za-z0-9_-]{22}$`). Collision generation retries; arbitrary user strings are rejected before lookup.
- A reference is stable across bridge restarts and session-file moves, does not expire automatically, and is explicitly revocable/regenerable. It is a pointer, not authorization: every resolve requires a valid bridge token.

### Durable bridge state

- Add `BRIDGE_STATE_DIR`, defaulting to `~/.pi-mobile`, and store the versioned mapping in `share-references.json`, outside the Pi session directory. Tests always inject a disposable directory and never read/write real user state.
- Store only bridge-state version plus `sessionId ↔ shareReference` mappings and lifecycle metadata—never tokens, cwd values, paths, titles or transcripts. Bound IDs to 1–128 printable non-whitespace ASCII characters without interpreting their semantics; bound the store to 100,000 mappings and 16 MiB.
- Create the directory/file with owner-only permissions where supported, serialize writes, use write-temp + atomic rename, and recover safely from an interrupted temp write. A corrupt/unsupported primary file disables only share create/resolve/revoke with `share_state_unavailable`; the core authenticated bridge remains operational. Never silently discard mappings. State loss invalidates links and must produce an actionable operator message, not regenerated references that appear equivalent.
- Duplicate live session IDs are non-shareable and non-resolvable until ambiguity is removed. They remain visible in normal session browsing; do not fail the whole session list.

### Link contracts

Canonical app URI:

```text
pimobile://open/v1/<shareReference>?host=<encoded-host>&port=<1..65535>&tls=<0|1>
```

Canonical self-hosted URL when `BRIDGE_SHARE_ORIGIN` is configured:

```text
<BRIDGE_SHARE_ORIGIN>/s/v1/<shareReference>
```

- `BRIDGE_SHARE_ORIGIN` is optional but strictly parsed as an `http` or `https` origin with host and optional port, and no userinfo, query or fragment. Reject ambiguous path prefixes in this plan.
- Expose configured `shareOrigin` as non-secret capability metadata in authenticated `bridge_hello` and the versioned pairing payload. Store it as an optional verified alias on the local HostProfile only after pairing review or authenticated hello; migrate old profiles with `null`. Incoming links may match endpoint origin or this verified alias. Alias changes require authenticated refresh and must not silently retarget a stored token.
- Generate landing content and app URIs from configured origin/profile data, never the request `Host` header.
- Normalize DNS case/trailing dot, bracketed IPv6 and default ports consistently; reject control characters, userinfo, unsupported schemes, duplicate parameters and unknown versions.
- An incoming authority may match only an already configured profile before any token is sent. An unmatched link may prefill non-secret host setup, but connection requires explicit review/save and token entry. Never auto-connect or auto-save an endpoint supplied by an external intent.

### Bridge operations

Add these authenticated bridge-channel operations (final names may follow existing constant conventions, but semantics are fixed):

- `bridge_get_or_create_session_share { sessionPath }` → stable `shareReference` plus optional configured web URL;
- `bridge_resolve_session_share { shareReference }` → exactly one current authenticated `SessionRecord`/internal path;
- `bridge_revoke_session_share { sessionPath }` → revoke the current mapping so the old reference no longer resolves.

`sessionPath` exists only inside the authenticated WebSocket payload and must be root-validated and excluded from logs. These are authenticated metadata operations and do not require cwd/control lock. Resuming the resolved record still uses the existing cwd/control-lock pipeline. All errors use stable sanitized bridge codes; unknown/revoked references are indistinguishable where practical, while corrupt state uses `share_state_unavailable`.

## Dependencies

- Plan 011's non-device baseline remains green.
- No central service, domain, signing key, device, emulator or ADB is required.
- Verified public Android App Links remain blocked on an owned domain plus stable signed release identity. This plan must not claim them; the self-hosted URL and custom scheme are the complete supported delivery paths for now.
- Plans 013–018 must consume the internal/external identity distinction above rather than inventing another locator.

## Scope

**In scope**:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/ayagmar/pimobile/MainActivity.kt`
- `app/src/main/java/com/ayagmar/pimobile/di/AppGraph.kt`
- focused files under `app/src/main/java/com/ayagmar/pimobile/hosts/**`, `sessions/**`, `ui/hosts/**`, and `ui/sessions/**`
- `app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt`
- `core-sessions/src/main/kotlin/com/ayagmar/pimobile/coresessions/SessionIndexModels.kt` and `SessionIndexRepository.kt`
- `bridge/src/session-indexer.ts`, `server.ts`, `protocol.ts`, `config.ts`, `pairing.ts`, and focused new share-state/link modules
- `bridge/.env.example`
- focused tests beside each owning module; sanitized RPC fixtures only if required
- `README.md`, `docs/bridge-protocol.md`, `docs/architecture.md`, `docs/onboarding.md`, `docs/testing.md`, `docs/release.md`
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- Cloud link service, account/per-user ACL system, push relay or simultaneous collaboration.
- Automatic lock takeover or changes to cwd/session ownership.
- Pi SDK migration or undocumented Pi RPC commands.
- Verified public-domain App Links before domain/signing prerequisites exist.
- Session metadata in the unauthenticated landing page.
- Device/emulator/ADB/manual acceptance.

## Steps

### Step 1: Add internal stable identity and pure locator contracts

Add nullable/backward-compatible `sessionId` to `SessionRecord`; old disk caches must deserialize and become shareable only after authenticated refresh. Update merge/deduplication to use a unique valid `(host, sessionId)` when available, so a moved file updates the existing record. Legacy records without IDs may use path only during compatibility merge and must never become shareable/pinnable from that fallback. Duplicate IDs stay separately browseable by path but are marked non-stable/non-shareable.

Add pure internal `SessionKey` and external `SharedSessionLocator` models/codecs. Extend replayable `ActiveSessionState` to carry the resolved local `SessionKey` plus generation while retaining `sessionPath` only for internal operations. During switch, publish an unresolved/switching target; after `switch_session`, read documented `get_state.sessionId` and require it to match the target record before publishing the active key or navigating. Legacy ordinary sessions may retain path-only behavior but cannot enter stable/share flows. Clear/restore generation state on failure so late responses cannot publish the wrong key. The external codec uses only authority, version and 22-character share reference. Add canonical round-trip, hostile URI, IDN/IPv6, duplicate parameter, unknown version, raw-session-ID rejection, redaction, active-state generation/mismatch and old-cache tests.

**Verify**:

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest
```

Expected: tests pass; moved records retain stable internal identity; no external-locator fixture contains a Pi session ID/path/cwd/token.

### Step 2: Index IDs and implement durable/revocable share references

Read only the first valid `type: "session"` header ID using existing bounded parser/cache conventions. Validate as documented opaque internal data without overfitting to one UUID spelling. Missing/malformed IDs disable stable features for that record; duplicate IDs disable share operations for all duplicates without breaking `bridge_list_sessions`.

Implement the injected share-reference store and authenticated create/resolve/revoke operations. On resolve, map reference → session ID → exactly one current indexed file; path/root validation remains authoritative. Cover restart persistence, atomic-write interruption, corruption/unsupported version, collision retry, bounded growth, revoke/regenerate, moved file, deleted file, duplicate ID, concurrent creates and state-directory permissions. Never log either session ID or share reference.

**Verify**:

```bash
(cd bridge && pnpm run check)
```

Expected: lint/typecheck/tests pass; list_sessions remains usable with malformed/duplicate records; no test touches host user state.

### Step 3: Add exact cold/warm Android routing

Add an application-scoped pending-target coordinator through `AppGraph`. In the manifest add a narrowly scoped `DEFAULT` + `BROWSABLE` custom-scheme filter and `singleTop` activity behavior. Handle only `ACTION_VIEW` external data. `onCreate()` submits the initial URI; `onNewIntent()` calls `super`, `setIntent(intent)`, then submits the new URI. Notification-local targets later use explicit immutable `PendingIntent` extras and internal `SessionKey`, not an exported URL.

Consume each request exactly once across recomposition/configuration changes. Give each resolve a generation/cancellation token so a slow old link cannot navigate after a newer link. For an existing exact endpoint-origin or previously verified share-origin alias match, authenticate against that profile's configured WebSocket endpoint—not an arbitrary URI endpoint—then call the dedicated bridge resolve operation and delegate to existing resume/navigation. Require the controller's post-switch `get_state.sessionId` to equal the resolved record before Chat navigation. Before successful resume, show only generic target state. For an unmatched authority, require explicit host review and token entry; never transmit an existing token to the supplied endpoint. If authenticated hello reports a share-origin alias mismatch, fail closed and require profile review.

Test cold start, warm delivery, repeated intent, two rapid links, process recreation/pending consumption, malformed action/data, matching profiles, ambiguous duplicate profiles, unmatched host, missing token, auth rejection, missing/revoked reference, deleted session, resume failure and lock denial.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: all non-device tests pass; manifest/lifecycle sources compile; no raw ID/path/credential enters route arguments or errors.

### Step 4: Add share, copy, revoke and generic landing UX

Add **Share session link**, **Copy link**, and **Revoke shared link** to the session action/detail surface. Creating a link calls the authenticated bridge operation; repeated creation returns the same reference until revoke. Revoke requires confirmation and invalidates the old link; a later share generates a different reference. Disable actions with explicit reason for legacy/malformed/duplicate IDs or unavailable bridge state.

Always support the canonical custom URI. When `BRIDGE_SHARE_ORIGIN` is configured, prefer the normal HTTP(S) URL in Android Sharesheet and retain custom URI fallback. The bridge landing route must:

- return the same metadata-free page for every syntactically valid reference;
- contain no session lookup/title/preview/path and no external asset;
- include an explicit **Open in Pi Mobile** link generated from configured origin, never request headers;
- set `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, frame protection and a restrictive CSP;
- avoid redirects that leak the custom URI through a referrer;
- never log request paths/references.

Add resolving, setup-required, authentication, unreachable, unavailable/revoked, corrupt bridge state, unsupported version, lock conflict, retry/cancel and successful navigation states. Lock conflict offers Retry/Open sessions only and never takeover.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
(cd bridge && pnpm run check)
git diff --check
```

Expected: actions/lifecycle/landing/security-header tests pass; generated links contain only authority/version/share reference.

### Step 5: Document security, lifecycle and operator-owned acceptance

Document exact bridge messages/configuration, state backup/reset consequences, reference stability/revocation, host matching, token requirements, duplicate-ID behavior and the distinction between local `SessionKey` and external locator. Add device steps—but do not run them—for cold/warm custom links, self-hosted browser landing, unmatched/ambiguous host, cancellation, token rejection, moved/deleted session, revoke/regenerate, two rapid links, rotation/process recreation and lock conflict.

Mark this plan and `plans/README.md` DONE only after non-device gates pass; leave device evidence `PENDING — operator-owned`.

**Verify**:

```bash
git diff --check
git status --short
```

Expected: only in-scope implementation, tests, docs and plan-status files changed.

## Regression expectations

- Core: old cache compatibility, ID-first merge, moved path, legacy fallback, duplicates.
- Bridge: header extraction, malformed/missing/duplicate IDs, durable/atomic reference store, collision/concurrency, create/resolve/revoke, deleted/moved session, generic landing and no-secret/no-ID logs.
- Android: pure codec, authority normalization, intent action/lifecycle/generation, profile matching, auth/resolve/resume, revoke and all error states.
- Existing reconnect, control-lock, index cache and exactly-one rebuild tests remain green.

## Verification

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
```

Expected: every command exits 0, audit reports no known production vulnerability, both APKs assemble, no device command runs, and diff check emits no output.

## Done criteria

- [ ] Core/bridge tests prove valid unique Pi IDs provide stable internal identity and moved-file continuity; duplicates never resolve arbitrarily.
- [ ] Bridge tests prove share references are random, durable, stable, revocable, atomically persisted and resolvable only after authentication.
- [ ] URI tests prove no Pi session ID, token, cwd, path, transcript or local profile ID is present in external links/errors.
- [ ] Activity/coordinator tests prove cold, warm, repeated, racing and recreated deliveries consume exactly once and cannot navigate stale targets.
- [ ] UI tests prove create/copy/share/revoke and all recovery states, including lock denial without takeover.
- [ ] Landing-page tests prove metadata-free invariant, configured-origin generation and required security headers.
- [ ] Complete non-device commands above exit 0; `git status --short` contains only in-scope changes.

## STOP conditions

Stop and report if:

- Pi 0.80.6 does not provide a stable internal session ID without private-format guessing.
- Any design puts raw `sessionId`, token, cwd, absolute path or transcript content into an external URI/log.
- Durable references cannot be stored atomically without reading/mutating host state in tests.
- An incoming link could cause a stored token to be sent to an endpoint that was not already explicitly configured.
- Resolve/resume would bypass authentication, cwd/control locks, generation guards, cursor synchronization or the exactly-one rebuild rule.
- A verification command fails twice after a reasonable correction, or a non-device criterion would require device execution.
