# Current Pi RPC gap assessment

**Assessed:** 2026-07-13
**Pi version:** 0.80.6
**Authority:** installed `@earendil-works/pi-coding-agent` `docs/rpc.md`

## Delivered by current Pi

- `get_entries` returns append-order entries, stable cursor IDs, and the current `leafId`.
- `get_tree` returns authoritative session topology and the current `leafId`.
- `clone` duplicates the active branch into a new session.

These capabilities are no longer Pi Mobile bridge feature gaps. Adoption in Pi Mobile is tracked separately; support in Pi does not imply that every current client path consumes it yet.

## Remaining gaps relevant to Pi Mobile

### Cross-project session discovery

Current Pi RPC has no `list_sessions` command. The bridge must continue indexing configured session directories so Android can browse inactive projects and sessions before a Pi process exists.

### Direct tree navigation

Current Pi RPC can read entries and topology but has no command that moves the active leaf to an arbitrary entry. Pi Mobile therefore retains its internal navigation extension and bridge control message.

### External-file freshness notification

RPC reports changes made by its own process but does not notify a running process when another process edits the session file. The bridge/client still need a bounded freshness fallback for external edits.

## Review policy

Re-check this assessment against the installed Pi RPC documentation during compatibility upgrades. Prefer standard RPC commands whenever they replace a bridge-specific capability, without moving session discovery or navigation until equivalent upstream contracts exist.
