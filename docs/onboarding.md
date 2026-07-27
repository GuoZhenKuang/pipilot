# Onboarding and recovery

## First successful connection

1. **Prepare the computer** — install Pi 0.80.6 or newer, start the authenticated bridge, and connect the phone and computer to the same Tailnet.
2. **Print pairing QR** — from `bridge/`, run `pnpm pair`. The command reads the existing `.env`, discovers the computer's Tailscale MagicDNS name, and prints a QR containing the host, port, TLS setting, and existing bridge token. If Tailscale discovery is unavailable, run `pnpm pair -- --host <reachable-hostname>`.
3. **Scan and save** — in Pi Mobile, open **Hosts**, tap **Scan QR**, scan the terminal code, review the populated connection, and save it. Manual entry remains available as a fallback. Keep the terminal QR private because it contains the bridge token.
4. **Validate readiness** — connection diagnostics distinguish reachability, authentication, and Pi RPC readiness. Use **Test** to verify all stages.
5. **Choose work** — the app opens Sessions after the first connection is saved. Choose a recent project/session or create a session.
6. **Chat** — an active session makes Chat primary. Back returns to Sessions.

**Hosts** remains available in the navigation drawer after setup so a broken connection can always be edited, tested, deleted, or replaced.

Non-secret connection fields may be re-entered when editing. Existing tokens remain encrypted unless the user explicitly types a replacement.

## Recovery map

| State | Explanation | Primary action |
|---|---|---|
| No network / bridge unreachable | Tailscale, address, port, or bridge process is unavailable | Check connectivity, then **Try again** |
| Authentication rejected | The supplied bridge token does not match | **Update token** |
| Bridge incompatible | Required authenticated RPC behavior is unavailable | Upgrade/restart the bridge |
| Pi missing or RPC unavailable | The bridge cannot start or query Pi | Install Pi 0.80.6+, then **Test Pi again** |
| No model credentials | Pi starts but cannot use a configured model | Configure provider credentials on the computer |
| Control lock held | Another client controls the cwd/session | Return to Sessions or release the other client |
| Session unavailable | Session was moved, deleted, or cannot be read | Refresh Sessions and choose another session |

Technical diagnostics may be copied for troubleshooting, but primary recovery text is typed and sanitized. Tokens, authorization headers, private prompts, and raw session contents must never be rendered or logged.

## Sharing a session

Set `BRIDGE_STATE_DIR` to an owner-only disposable/backup location and optionally set `BRIDGE_SHARE_ORIGIN` to a strict HTTP(S) origin with no path, query, fragment, or userinfo. Pair or authenticate the host, review the reported origin, then use **Share session link**. Repeated creation is stable until **Revoke shared link**; revocation is durable and the next share generates a different reference. Back up the state directory: state loss invalidates old links and must not silently regenerate equivalent references.

A shared URI contains only the bridge authority, link version, and opaque reference. Opening it never sends a stored token to an endpoint that is not already configured and matched (or explicitly reviewed and saved). Setup-required and authentication states require user action. Missing, revoked, deleted, duplicate-ID, corrupt-state, unsupported-version, unreachable, resume-failure, and lock-conflict states are generic; lock conflict offers retry or return to Sessions and never takeover.

The bridge indexes only the first bounded session header. A valid unique Pi header ID remains stable when its file moves; malformed/missing IDs are browseable but cannot be shared, and duplicate live IDs are never resolved arbitrarily.
