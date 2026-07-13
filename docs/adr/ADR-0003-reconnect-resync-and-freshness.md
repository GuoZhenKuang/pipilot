# ADR-0003: Recover with resync and protect against cross-device drift

- **Status:** Accepted
- **Date:** 2026-02-18

## Context

Mobile networks are intermittent. Socket reconnect alone is insufficient because client state may diverge from server/runtime state after temporary disconnects or edits from another client.

Risks:

- stale timeline after reconnect
- wrong streaming flags
- user editing on outdated session state

## Decision

Use a two-part consistency strategy:

1. **Reconnect + deterministic resync**
   - transport reconnects with backoff
   - on reconnect, client waits for `bridge_hello`, reapplies cwd/control
   - then fetches `get_state` + `get_entries { since: lastEntryId }`
   - reconciles the documented entry graph against `leafId`
   - performs exactly one explicit full rebuild for an unknown cursor, branch move, session replacement, unsupported entry, or invalid projection

2. **Session invalidation plus safety monitoring**
   - bridge pushes `bridge_session_invalidated` for mutations observed through Pi, imports, and navigation
   - client immediately performs cursor resync
   - a 60-second safety poll runs only while chat is foreground-active to detect terminal or external file edits
   - on unsafe mismatch while the user is busy, show a coherency warning and **Sync now** action

## Consequences

### Positive

- Stronger eventual consistency after transient disconnections.
- Better protection against cross-device write conflicts.
- Clear user affordance when stale state is suspected.

### Negative

- A low-frequency safety request remains necessary for edits outside the active Pi process.
- More client-side state/UX complexity (warning + sync paths).

## Alternatives considered

1. **Reconnect without explicit resync**
   - Rejected: stale local state can persist unnoticed.
2. **Manual sync only (no polling)**
   - Rejected: poor UX; users often miss hidden divergence.
3. **Server push invalidation only**
   - Rejected: the bridge cannot observe direct terminal writes to the session file.
