# Bridge Protocol Reference

This document describes the WebSocket protocol between the Android client and the Pi Mobile bridge.

## Table of Contents

- [Transport and Endpoint](#transport-and-endpoint)
- [Authentication](#authentication)
- [Envelope Format](#envelope-format)
- [Connection Handshake](#connection-handshake)
- [Bridge Channel Messages](#bridge-channel-messages)
- [RPC Channel Messages](#rpc-channel-messages)
- [Errors](#errors)
- [Health Endpoint](#health-endpoint)
- [Practical Message Sequence](#practical-message-sequence)
- [Reference Files](#reference-files)

## Transport and Endpoint

- Protocol: WebSocket
- Endpoint: `ws://<host>:<port>/ws`
- Optional reconnect identity: `?clientId=<uuid>`

All messages are JSON envelopes with one of two channels:

- `bridge` (control plane)
- `rpc` (pi RPC payloads)

## Authentication

A valid bridge token is required.

Supported headers:

- `Authorization: Bearer <token>`
- `x-bridge-token: <token>`

Notes:

- token in query string is not accepted
- invalid token -> HTTP 401 on websocket upgrade

## Envelope Format

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_ping"
  }
}
```

```json
{
  "channel": "rpc",
  "payload": {
    "id": "req-1",
    "type": "get_state"
  }
}
```

Validation rules:

- envelope must be JSON object
- `channel` must be `bridge` or `rpc`
- `payload` must be JSON object

## Connection Handshake

After WebSocket connect, bridge sends:

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_hello",
    "clientId": "...",
    "resumed": false,
    "cwd": null,
    "reconnectGraceMs": 30000,
    "message": "Bridge skeleton is running"
  }
}
```

If reconnecting with same `clientId`, `resumed` may be `true` and previous `cwd` is restored.

## Bridge Channel Messages

### Request → Response map

| Request `payload.type` | Response `payload.type` | Notes |
|---|---|---|
| `bridge_ping` | `bridge_pong` | Liveness check |
| `bridge_list_sessions` | `bridge_sessions` | Returns grouped session metadata |
| `bridge_get_session_tree` | `bridge_session_tree` | Requires `sessionPath`; supports filter |
| `bridge_get_session_freshness` | `bridge_session_freshness` | Returns freshness fingerprint + lock owner metadata |
| `bridge_import_session_jsonl` | `bridge_session_imported` | Requires control lock; uploads a JSONL session into the active runtime session dir and switches to it |
| `bridge_navigate_tree` | `bridge_tree_navigation_result` | Requires control lock; uses internal extension command |
| `bridge_set_cwd` | `bridge_cwd_set` | Sets active cwd context for client |
| `bridge_acquire_control` | `bridge_control_acquired` | Acquires write lock for cwd/session |
| `bridge_release_control` | `bridge_control_released` | Releases held lock |
| `bridge_get_or_create_session_share` | `bridge_session_share` | Authenticated stable opaque reference for one unique indexed session |
| `bridge_resolve_session_share` | `bridge_session_share_resolved` | Authenticated lookup; duplicate/deleted/revoked identities resolve generically unavailable |
| `bridge_revoke_session_share` | `bridge_session_share_revoked` | Authenticated owner action; next create generates a new reference |

The bridge also pushes `bridge_session_invalidated { reason }` to the controlling client after mutations observed through the active Pi process, session import/switch, or tree navigation. Clients should immediately run cursor synchronization.

### Stable session sharing

The bridge reads the first bounded Pi session header and uses its documented `id` as the internal identity. Valid duplicate IDs remain listed but are not shareable or resolvable. A moved file retains its mapping because references point to the ID, not a path.

The three share operations above are metadata-only authenticated bridge operations and do not bypass cwd/control locks. Resolve returns exactly one current `SessionRecord`; resuming it uses the ordinary cwd setup, control lock, `switch_session`, cursor synchronization, and post-switch `get_state.sessionId` check.

References are 16 random bytes encoded as unpadded base64url (22 characters). The versioned owner-only state is `${BRIDGE_STATE_DIR}/share-references.json`, written through a synced temporary file and atomic rename. It contains no tokens, paths, cwd values, titles, previews, or transcript data. Corrupt/unsupported state returns `share_state_unavailable` while normal listing and RPC remain available. Revoke is durable and explicit; deleting/resetting the state invalidates links and requires operator repair/recovery.

When `BRIDGE_SHARE_ORIGIN` is configured as a strict HTTP(S) origin, landing pages are `/s/v1/<reference>` and contain only generic open-in-app instructions. They use no request `Host` header and return `no-store`, no-referrer, `nosniff`, frame protection, and restrictive CSP headers. The custom fallback is `pimobile://open/v1/<reference>?host=<authority>&port=<port>&tls=<0|1>`.

### `bridge_get_session_tree` filters

Allowed values:

- `default`
- `all`
- `no-tools`
- `user-only`
- `labeled-only`

Unknown filter -> `bridge_error` (`invalid_tree_filter`).

The bridge rejects WebSocket messages larger than `BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES` (16 MiB by default).

### `bridge_get_session_freshness`

Request payload:

```json
{
  "type": "bridge_get_session_freshness",
  "sessionPath": "/.../session.jsonl"
}
```

Response payload:

```json
{
  "type": "bridge_session_freshness",
  "sessionPath": "/.../session.jsonl",
  "cwd": "/.../project",
  "fingerprint": {
    "mtimeMs": 1730000000000,
    "sizeBytes": 2048,
    "entryCount": 42,
    "lastEntryId": "m42",
    "lastEntriesHash": "..."
  },
  "lock": {
    "cwdOwnerClientId": "client-a",
    "sessionOwnerClientId": "client-a",
    "isCurrentClientCwdOwner": true,
    "isCurrentClientSessionOwner": true
  }
}
```

### `bridge_import_session_jsonl`

Request payload:

```json
{
  "type": "bridge_import_session_jsonl",
  "fileName": "shared-session.jsonl",
  "content": "{\"type\":\"session\",...}\n{...}\n"
}
```

Response payload:

```json
{
  "type": "bridge_session_imported",
  "sessionPath": "/.../shared-session.jsonl"
}
```

Notes:

- requires cwd context + control lock
- writes the uploaded JSONL into the bridge session directory
- switches the active pi runtime to the imported session
- filename is sanitized and uniqued server-side to avoid path traversal and overwrites
- UTF-8 content is limited by `BRIDGE_IMPORT_MAX_BYTES` (10 MiB by default); oversized content returns `import_payload_too_large` without closing the connection

### `bridge_navigate_tree`

Request payload:

```json
{
  "type": "bridge_navigate_tree",
  "entryId": "entry-42"
}
```

Response payload:

```json
{
  "type": "bridge_tree_navigation_result",
  "cancelled": false,
  "editorText": "retry from here",
  "currentLeafId": "entry-42",
  "sessionPath": "/.../session.jsonl"
}
```

## RPC Channel Messages

`rpc` channel forwards pi RPC commands/events unchanged. Android uses `get_entries` for active-session synchronization and `get_tree` for active topology. Cross-project session listing and inactive-session tree browsing remain bridge-owned. Direct tree navigation remains bridge-owned because Pi 0.80.6 does not expose a navigation RPC command.

### Preconditions for sending RPC payloads

Client must have:

1. cwd context (`bridge_set_cwd`)
2. control lock (`bridge_acquire_control`)

Otherwise bridge returns `bridge_error` with code `control_lock_required`.

### Forwarding behavior

- Request payload is forwarded to cwd-specific pi subprocess stdin
- pi stdout events are wrapped as `{ channel: "rpc", payload: ... }`
- events are delivered only to the controlling client for that cwd

## Errors

Bridge errors always use:

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_error",
    "code": "error_code",
    "message": "Human readable message"
  }
}
```

Common codes:

- `malformed_envelope`
- `unsupported_bridge_message`
- `missing_cwd_context`
- `invalid_cwd`
- `invalid_session_path`
- `invalid_tree_filter`
- `invalid_tree_entry_id`
- `invalid_import_payload`
- `control_lock_required`
- `control_lock_denied`
- `invalid_rpc_payload`
- `rpc_forward_failed`
- `tree_navigation_failed`
- `session_index_failed`
- `session_tree_failed`
- `session_freshness_failed`
- `session_import_failed`
- `import_payload_too_large`

## Health Endpoint

Optional HTTP endpoint:

- `GET /health`
- enabled by `BRIDGE_ENABLE_HEALTH_ENDPOINT=true`

Response includes:

- uptime
- process manager stats
- connected/reconnectable client counts

When disabled, `/health` returns 404.

## Practical Message Sequence

Minimal sequence for a typical RPC session:

1. Connect websocket with auth token
2. Receive `bridge_hello`
3. Send `bridge_set_cwd`
4. Send `bridge_acquire_control`
5. Send `rpc` command payloads (`get_state`, `prompt`, ...)
6. Receive `rpc` events and `response` payloads
7. Optionally send `bridge_release_control`

## Reference Files

- `bridge/src/protocol.ts`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/PiRpcConnection.kt`
- `bridge/test/server.test.ts`
