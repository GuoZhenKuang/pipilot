# Pi RPC compatibility

Pi Mobile requires and is tested with `@earendil-works/pi-coding-agent` **0.80.6 or newer within the 0.80 release line**. Version 0.80.6 is the minimum because the client relies on the `agent_settled` lifecycle boundary and the `max` thinking level.

The bridge remains a transparent JSON pass-through. It must not normalize Pi command IDs, fields, or asynchronous events.

## Upgrade procedure

1. Read the installed Pi `CHANGELOG.md` and `docs/rpc.md` completely.
2. Update the documented minimum only after compatibility tests pass.
3. Refresh sanitized files under `core-rpc/src/test/resources/rpc/`; never copy credentials, paths, prompts, or private session content.
4. Run `./gradlew :core-rpc:test :core-net:test :app:testDebugUnitTest` and `(cd bridge && pnpm run check)`.
5. Exercise retry, compaction, queued continuation, abort, tree, and reconnect behavior against the installed Pi.

## Consumed current capabilities

The typed contract includes `agent_settled`, `get_entries`, `get_tree`, `clone`, canonical `contextUsage`, and thinking level `max`. Tree and incremental synchronization adoption is tracked separately from protocol typing.
