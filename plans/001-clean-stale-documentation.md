# Plan 001: Clean stale documentation and record the RPC architecture decision

> **Executor instructions**: Follow this plan step by step. Run every verification command before continuing. Do not implement runtime changes. Update this plan's row in `plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat ca7eaa2..HEAD -- README.md docs plans`
> If the cited stale statements no longer exist, stop and report which parts were already resolved.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `ca7eaa2`, 2026-07-12

## Why this matters

The repository simultaneously describes the current RPC subprocess architecture and an untracked plan to replace it with the SDK. Several documents also describe RPC gaps that current Pi has closed. A fresh executor could follow the wrong architecture. This plan makes RPC the explicit maintained decision and separates historical records from current documentation.

## Current state

- `README.md:70` installs the old `@mariozechner/pi-coding-agent` package.
- `README.md:75` uses `github.com/yourusername/pi-mobile.git`.
- `docs/ai/pi-bridge-sdk-migration-spec-plan.md` is an untracked, stale SDK migration proposal. Do not preserve it as an active plan.
- `docs/pi-upstream-opportunities.md` is untracked and claims RPC lacks `get_tree`; current Pi RPC provides `get_entries`, `get_tree`, and `clone`.
- `docs/spikes/tree-navigation-rpc-vs-bridge.md`, `docs/final-acceptance.md`, and ADR-0001 contain historically valid but now stale statements about tree/session capabilities.
- ADR-0002 records the still-valid one-process-per-cwd and locking decision.

Authoritative local Pi docs:

- `/home/ayagmar/.fnm/node-versions/v24.12.0/installation/lib/node_modules/@earendil-works/pi-coding-agent/README.md`
- corresponding `docs/rpc.md`

If these paths do not exist in the executor environment, use the docs bundled with the installed `@earendil-works/pi-coding-agent`; do not rely on memory.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Find stale references | `rg -n '@mariozechner|yourusername|SDK migration|get_tree|get_entries|list-sessions' README.md docs --glob '*.md'` | only intentional historical references remain |
| Markdown links | `find README.md docs -name '*.md' -print0 \| xargs -0 grep -nE '\]\([^)]*\.md[^)]*\)'` | reviewable link list |
| Working tree | `git status --short` | only scoped docs and plan status changed |

## Scope

**In scope**:
- `README.md`
- `docs/README.md`
- `docs/architecture.md`
- `docs/codebase.md`
- `docs/extensions.md`
- `docs/bridge-protocol.md`
- `docs/final-acceptance.md`
- `docs/pi-upstream-opportunities.md`
- `docs/spikes/tree-navigation-rpc-vs-bridge.md`
- `docs/adr/ADR-0001-bridge-required.md`
- `docs/adr/ADR-0002-cwd-process-and-locking.md`
- `docs/adr/README.md`
- `docs/adr/ADR-0004-retain-rpc-subprocess-boundary.md` (create)
- `docs/ai/pi-bridge-sdk-migration-spec-plan.md`
- `plans/README.md`

**Out of scope**:
- Runtime source or tests
- Rewriting historical progress files under `docs/ai/`
- Claiming `list_sessions` exists in Pi RPC; current RPC has tree/entry commands but session discovery remains bridge-owned

## Git workflow

Use branch `advisor/001-clean-stale-docs`. Follow conventional commits; example: `docs: align architecture with current pi rpc`. Do not push unless asked.

## Steps

### Step 1: Correct user-facing setup

Update package name to `@earendil-works/pi-coding-agent`, replace the placeholder clone URL with the repository's actual origin (`git remote get-url origin`), and state a supported Pi version/range based on the installed package version. Add commands to verify `pi --version`, bridge startup, and `/health` when enabled. Do not expose values from `bridge/.env`.

**Verify**: `rg -n '@mariozechner|yourusername' README.md` returns no matches.

### Step 2: Record the retained architecture

Create ADR-0004. It must say:

- Android → authenticated WebSocket bridge → isolated `pi --mode rpc` subprocess per cwd.
- SDK embedding was reconsidered and rejected for this roadmap because RPC gives process isolation and avoids implementing an RPC-compatibility dispatcher.
- The bridge remains responsible for auth, transport, discovery, locks, reconnect policy, and only those Pi gaps not available through RPC.
- Revisit SDK embedding only with measured process-cost evidence and a protocol-conformance suite.

Update ADR index and architecture/codebase docs accordingly.

**Verify**: `rg -n 'ADR-0004|one.*process.*cwd|pi --mode rpc' docs/adr docs/architecture.md docs/codebase.md` finds the new decision and current architecture.

### Step 3: Retire stale active plans and qualify historical documents

Delete the untracked SDK migration proposal rather than rewriting it. Rewrite `docs/pi-upstream-opportunities.md` as a dated current-gap assessment: mark `get_tree` and `get_entries` as delivered upstream, identify session listing and direct navigation/freshness only if still absent in the current installed RPC docs. Add a prominent “historical spike” notice to the old tree spike and final acceptance report; do not falsify their original results.

Update `docs/README.md` to distinguish maintained docs from historical `docs/ai` artifacts.

**Verify**:

```bash
test ! -e docs/ai/pi-bridge-sdk-migration-spec-plan.md
rg -n 'historical|superseded|current Pi' docs/README.md docs/final-acceptance.md docs/spikes/tree-navigation-rpc-vs-bridge.md docs/pi-upstream-opportunities.md
```

Expected: deleted stale migration file and explicit historical/current labels.

### Step 4: Synchronize bridge and extension docs

Document that current source still uses bridge-owned session listing and internal navigation/workflow extensions, while plans 003–004 may simplify this later. Do not describe future work as already implemented.

**Verify**: manually compare documented extension filenames and bridge message names with `bridge/src/extensions/` and `bridge/src/server.ts`; all referenced names exist.

## Test plan

Documentation-only. Run stale-reference searches and check all relative Markdown links resolve with a small script or available link checker. At minimum, extract local `.md` links and test each target exists.

## Done criteria

- [ ] Old package and placeholder repository references are gone.
- [ ] ADR-0004 records the RPC decision and is indexed.
- [ ] SDK migration proposal is removed.
- [ ] Historical reports are clearly labeled.
- [ ] Current Pi-delivered RPC features are no longer listed as missing.
- [ ] No runtime files changed.
- [ ] Plan row is DONE.

## STOP conditions

- The installed Pi RPC docs no longer expose `get_entries` or `get_tree`.
- `git remote get-url origin` does not identify a canonical repository URL.
- Any requested documentation correction requires claiming unimplemented runtime behavior.

## Maintenance notes

Reviewers should distinguish “supported by Pi” from “already consumed by Pi Mobile.” Update the compatibility statement whenever the pinned/tested Pi version changes.
