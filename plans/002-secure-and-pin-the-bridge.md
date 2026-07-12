# Plan 002: Secure, pin, and bound the bridge runtime

> **Executor instructions**: Execute every step and verification gate. Preserve the external WebSocket envelope and error shapes. Update `plans/README.md` when done.
>
> **Drift check**: `git diff --stat ca7eaa2..HEAD -- bridge README.md docs/bridge-protocol.md plans`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/001-clean-stale-documentation.md`
- **Category**: security
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

The bridge is a network-facing remote-code-control service. Its current `ws` version has a high-severity denial-of-service advisory, the WebSocket server has no project-specific payload bound, and the repository does not pin or verify the Pi executable it forwards to.

## Current state

- `bridge/package.json` declares `ws: ^8.18.3`; `pnpm audit --prod` reports GHSA-96hv-2xvq-fx4p, patched in 8.21.0.
- `bridge/src/server.ts:107`: `new WebSocketServer({ noServer: true })`.
- Session import accepts a JSONL string and writes it after validation; no documented upload-size setting exists.
- `bridge/src/server.ts` creates forwarders using the `pi` command from PATH.
- Configuration parsing conventions live in `bridge/src/config.ts` and tests in `bridge/test/config.test.ts`.
- Server validation tests use `bridge/test/server.test.ts`; match these patterns.

## Commands

| Purpose | Command | Expected |
|---|---|---|
| Install | `cd bridge && pnpm install --frozen-lockfile` | exit 0 |
| Check | `cd bridge && pnpm run check` | lint/typecheck/56+ tests pass |
| Audit | `cd bridge && pnpm audit --prod` | no high vulnerabilities |

## Scope

**In scope**: `bridge/package.json`, `bridge/pnpm-lock.yaml`, `bridge/src/config.ts`, `bridge/src/server.ts`, relevant bridge tests, `README.md`, `docs/bridge-protocol.md`, `plans/README.md`.

**Out of scope**: SDK migration, Android UI, auth redesign, TLS termination, changing existing envelope/error shapes.

## Git workflow

Branch `advisor/002-secure-bridge`; conventional commit example `fix(bridge): bound websocket payloads`.

## Steps

### Step 1: Upgrade vulnerable runtime dependencies

Verify the latest stable compatible `ws` 8.x version from npm/official release information, choose at least 8.21.0, update lockfile, and record the version/rationale in the commit body or plan status note. Do not upgrade unrelated major versions.

**Verify**: `cd bridge && pnpm audit --prod` reports no high advisory for `ws`; `pnpm run check` passes.

### Step 2: Add explicit limits

Add parsed positive integer configuration for WebSocket maximum payload and imported session maximum bytes, with conservative documented defaults. Pass `maxPayload` to `WebSocketServer`. Reject oversized imports before parsing/writing using the existing `bridge_error` shape and a stable new error code. Ensure UTF-8 byte length, not JavaScript character count, is checked.

Add tests for defaults, overrides, invalid settings, exact-boundary acceptance, over-bound rejection, and connection survival after a rejected import.

**Verify**: `cd bridge && pnpm test -- config.test.ts server.test.ts` passes with new tests.

### Step 3: Make the Pi executable and compatibility visible

Add `BRIDGE_PI_COMMAND` with default `pi`; use it when creating forwarders. At startup, run a bounded `pi --version` probe before accepting traffic, log the detected version without credentials, and fail with an actionable error if Pi is absent. If exact supported-version enforcement cannot be established from repository policy, warn on unknown versions rather than inventing a range.

Tests must inject a fake probe—never require global Pi during unit tests.

**Verify**: bridge tests cover success, missing executable, timeout, and configured command.

### Step 4: Document operations

Add all new variables to configuration docs and create `bridge/.env.example` containing placeholders only. Ensure `.env` remains ignored and no secret value is copied.

**Verify**: `git check-ignore bridge/.env` succeeds; `git diff --check` succeeds.

## Test plan

Use existing config/server test structure. Add boundary-focused tests and a startup-probe unit test. Run full bridge check and production audit.

## Done criteria

- [ ] Patched `ws` is locked.
- [ ] WebSocket and import payloads are bounded and tested.
- [ ] Pi command is configurable and startup failure is actionable.
- [ ] `.env.example` has placeholders only.
- [ ] Existing wire shapes remain compatible.
- [ ] Plan row is DONE.

## STOP conditions

- Fixing the advisory requires a `ws` major upgrade with API changes.
- Current server tests cannot inject startup/process dependencies without an architectural refactor larger than this plan.
- Any test would need a real credential or globally installed Pi.

## Maintenance notes

Review payload defaults against real large-session imports. Limits protect memory but should produce a clear user-facing recovery path, not a generic disconnect.
