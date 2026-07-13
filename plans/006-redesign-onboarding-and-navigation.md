# Plan 006: Redesign onboarding, navigation, and recovery UX

> **Executor instructions**: Build the smallest complete mobile journey using existing Compose/Material 3 components. Test state and navigation, then verify on device. Update `plans/README.md` when done.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- app/src docs README.md plans`

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: MED
- **Depends on**: `plans/005-decompose-chat-architecture.md`
- **Category**: direction
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

First launch currently opens a raw Hosts CRUD screen and expects users to understand Tailscale, bridge binding, TLS, ports, and tokens. Hosts, Sessions, Chat, and Settings are peer drawer destinations even when they are unusable. The revived app needs a guided first success and clear recovery states.

## Current state

- `PiMobileApp.kt` starts on Hosts with no profiles, otherwise Sessions.
- Navigation is a modal drawer opened by a partially off-screen 34dp button and transparent scrim.
- Host editor has name/host/port/token/TLS fields with one generic error and a separate Test action.
- Diagnostics already distinguish network, auth, and RPC errors in `ConnectionDiagnostics`.
- Sessions screen supports host/cwd selection, search, new/resume, and active-session actions.
- Reuse project components and Material theme; do not introduce a design-system dependency.

## Commands

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
./gradlew ktlintCheck detekt test
```

## Scope

**In scope**: app navigation, onboarding/host/session screens and ViewModels, reusable components/theme only as needed, tests, user docs, plan status.

**Out of scope**: QR token transport unless it can be delivered without a new runtime dependency, bridge auth redesign, offline Pi, tablet-specific multi-pane UI, protocol changes.

## Steps

### Step 1: Define and test the journey

Write a short product flow in `docs/onboarding.md`:

1. Welcome and prerequisites.
2. Add bridge connection.
3. Test with staged results: network → auth → Pi/RPC readiness.
4. Save only after validation or explicitly allow “save and fix later.”
5. Choose recent/pinned project/session or create new.
6. Enter chat.

Define recovery actions for no network, auth rejected, bridge incompatible, Pi missing, no model credentials, lock held, and session unavailable.

**Verify**: document maps every state to one primary action.

### Step 2: Implement first-run onboarding

Create a dedicated onboarding route/state owner. Prefill safe defaults (port 8787, TLS based on selected connection guidance), explain where values come from, preserve token secrecy, validate fields inline, and combine test/save/continue into a clear sequence. Never display a stored token.

Add back/cancel semantics and process-death-safe persistence for non-secret draft fields only if existing storage patterns support it simply.

**Verify**: unit/Compose tests cover success and each recovery state.

### Step 3: Replace global drawer hierarchy

Use a simple adaptive top-level structure:

- No configured host: onboarding only.
- Configured but no active session: Home/Sessions dashboard with host switcher and settings access.
- Active session: Chat as primary, with back/up to sessions and contextual actions.

Use standard top app bars/navigation components with minimum 48dp touch targets and a normal scrim where modal navigation remains. Remove destinations that lead to unusable empty screens.

**Verify**: navigation tests cover cold start, onboarding completion, resume, new session, back, host switch, and active chat restoration.

### Step 4: Improve dashboard and empty states

Make recent sessions the primary content. Add clear actions for “New session,” “Add another computer,” refresh/retry, and search. Use human-readable cwd labels while keeping full paths available in secondary detail. Empty/error/loading states must not be plain standalone text.

**Verify**: Compose tests assert primary action availability for every state.

### Step 5: Make errors actionable

Map typed diagnostic/controller failures to concise title, explanation, and recovery action. Do not show raw exception text as primary copy; retain sanitized technical detail behind an expandable/copyable diagnostics action. Include lock owner guidance without exposing sensitive IDs unnecessarily.

**Verify**: tests cover mapping and ensure token values never appear in rendered error text.

### Step 6: Accessibility and device validation

Check content descriptions, semantics, font scaling, contrast, 48dp targets, keyboard/IME behavior, and screen-reader order. Run existing androidTests plus manual validation at default and 1.3–1.5x font scale, portrait and landscape.

## Test plan

Add ViewModel tests for flow state and Compose tests for navigation/semantics. Manual device loop: clean install, failed network, bad token, valid bridge, no sessions, resume, create, reconnect, lock contention, rotate, background/restore.

## Done criteria

- [ ] Clean install reaches a guided setup, not CRUD.
- [ ] A successful test continues to sessions without drawer hunting.
- [ ] Top-level navigation reflects current app state.
- [ ] Every common failure has an actionable recovery.
- [ ] Stored tokens are never rendered or logged.
- [ ] Accessibility/device checklist passes.
- [ ] Full gates pass and plan row is DONE.

## STOP conditions

- UX requires new bridge protocol behavior; report and isolate it rather than improvising.
- Navigation rewrite would lose active-session restoration semantics.
- A new dependency is required for QR/pairing; defer that optional feature.

## Maintenance notes

Optimize for first successful prompt, not maximum configuration flexibility. QR pairing is a follow-up once the manual flow is secure and reliable.
