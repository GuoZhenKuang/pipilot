# Plan 015: Add fork lineage and workspace-oriented session organization

> Fresh-context executor: read this plan completely before editing. Use documented Pi header fields and Plan 012 internal IDs. `parentSession` is a documented exact path reference; it may be resolved safely, but never infer parentage from filename similarity, cwd, timestamps, titles or prompts.

## Status

- State: TODO
- Priority: P2
- Effort: M–L (approximately 1–2 focused engineering weeks)
- Depends on: Plans 012–013; Plan 014 decision must be settled (DONE or BLOCKED) before editing shared docs, but foreground continuity is not a functional prerequisite
- Category: session model / discovery / UX

## Objective

Show reliable parent/fork families and friendly workspace grouping across session files without changing Pi's authoritative active `get_tree`. Resolve documented parent references to internal IDs, retain clearly marked historical resolution across a parent-file move when exact cached evidence exists, handle malformed/cyclic/ambiguous data safely, and integrate lineage into Plan 013 search/cockpit without exposing paths in normal UI.

## Baseline and dependency drift

- Authoring baseline: `d47ab00`.
- At execution verify Plans 012–013 are DONE and Plan 014 is settled as DONE or BLOCKED with evidence. Record `PLAN_BASELINE=$(git rev-parse HEAD)`, inventory predecessor changes, and compare implementation work to that commit:

```bash
git status --short --branch
PLAN_BASELINE=$(git rev-parse HEAD)
printf 'Plan 015 baseline: %s\n' "$PLAN_BASELINE"
git diff --stat d47ab00.."$PLAN_BASELINE" -- \
  bridge/src/session-indexer.ts bridge/test/session-indexer.test.ts \
  core-sessions app/src/main/java/com/ayagmar/pimobile/sessions \
  app/src/main/java/com/ayagmar/pimobile/ui/sessions app/src/test docs plans
```

Re-read the final Plan 012 index identity and Plan 013 presentation models. STOP if duplicate-ID behavior or stable merge semantics differ from those contracts.

## Current state at the authoring baseline

- Pi `session-format.md` documents header `id`, optional exact `parentSession` path, and in-file entry `id`/`parentId` topology.
- `bridge/src/session-indexer.ts` root-bounds direct session paths but exposes no parent identity.
- Authoring-baseline `SessionRecord`/`SessionGroup` are flat cwd groups. Plan 012 adds internal IDs and ID-first merge; Plan 013 adds workspace labels/cockpit state.
- Existing fork and active tree UI remains authoritative for in-file branches. This plan concerns relationships between session files only.

## Decisions and invariants

- Resolve a live parent only when the child's documented `parentSession` canonical/real path exactly matches one indexed file inside the configured session root and that file has one valid unique ID.
- Preserve the documented `parentSession` value only as internal authenticated/cache metadata, subject to the same root/path protections as `sessionPath`; never use or display it as lineage identity. If that exact path disappears after previously resolving, the core cache may retain `parentSessionId` with status `historical` only when the child ID and exact stored parent-path value are unchanged from the previous revision. A changed child header or live file now occupying that path invalidates/supersedes historical evidence.
- Resolution status is explicit: `live`, `historical`, `missing`, `outsideRoot`, `malformed`, `ambiguous` or `cycle`. Unknown states never become guessed families.
- Detect self-parent and multi-node cycles with bounded traversal. Mark affected edges invalid/cycle; do not drop unrelated sessions or recurse indefinitely.
- `parentSessionId` is authenticated/internal metadata. It is not placed in URLs or logs.
- “Workspace” means the current bridge's cwd grouping rendered through Plan 013's friendly label. This plan does not create an independent project database. Full/relative paths remain secondary technical details only.

## Scope

**In scope**:

- `bridge/src/session-indexer.ts` and focused bridge lineage modules/tests/fixtures
- `core-sessions/src/main/kotlin/com/ayagmar/pimobile/coresessions/**`
- `app/src/main/java/com/ayagmar/pimobile/sessions/**` only for lineage projection/state
- `app/src/main/java/com/ayagmar/pimobile/ui/sessions/**`
- focused tests and sanitized fixtures
- `README.md`, `docs/architecture.md`, `docs/testing.md`
- `plans/README.md` and this plan's status/evidence

**Out of scope**:

- Active `get_tree`, `bridge_navigate_tree`, cursor/rebuild behavior or session-file mutation.
- New Pi RPC commands, parent rewriting, session merge or collaboration.
- User-created project entities/database.
- Path display in normal cards/semantics/search.
- Device acceptance.

## Steps

### Step 1: Parse and resolve exact parent evidence

Extend authenticated index/core metadata with optional internal `parentSessionPath`, then perform deterministic two-pass bridge resolution after all live unique IDs/canonical paths are known. Apply root/realpath checks before resolution and exclude the field from URLs, logs and presentation models. In core cache merge, preserve prior exact `parentSessionPath → parentSessionId` evidence only with explicit historical status; bound it to currently cached child records and invalidate it when child ID/header changes or a live path is reused.

Handle missing/outside/malformed/ambiguous/self/cycle states without failing normal list_sessions. Ensure metadata/tree caches invalidate when child header, parent file, symlink target, deletion or path reuse changes.

**Verify**:

```bash
(cd bridge && pnpm run check)
```

Expected: tests cover direct/multi-level live lineage, moved parent with exact historical evidence, cold start after move without evidence, path reuse, missing/outside/symlink parent, malformed/duplicate ID, self-cycle/multi-cycle and revision invalidation.

### Step 2: Extend core models and deterministic family projection

Add optional `parentSessionId` plus resolution status with backward-compatible cache defaults. Build families with bounded iterative traversal, deterministic root/fork ordering (`updatedAt`, then stable internal key), no duplicate display and explicit orphan/cycle handling. Old caches without lineage remain readable and are refreshed normally.

**Verify**:

```bash
./gradlew :core-sessions:test :app:testDebugUnitTest
```

Expected: serialization, old cache, family ordering, historical/missing/ambiguous/cycle and deletion tests pass; no path fallback creates a family.

### Step 3: Render lineage and friendly workspace context

Add accessible, low-noise family indicators to Plan 013 cockpit/search: parent/fork count, “forked from” relation, expandable family or dedicated lineage filter. Keep active session primary. Render friendly host/workspace labels only; no full or relative path in normal cards/semantics. Explain historical/missing/cycle states in a secondary detail/recovery surface without exposing internal IDs.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: compact/expanded, live/historical/orphan/ambiguous/cycle and accessibility tests pass/compile; rendered strings contain no raw path or ID.

### Step 4: Add lineage search/filter and documentation

Add deterministic filters for family roots, forks, standalone and unresolved relations. Preserve Plan 013 all-host cache/error behavior. Document exact-vs-historical evidence and that active Pi tree remains authoritative.

**Verify**:

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
git status --short
```

Expected: all commands pass; a `git diff --stat <recorded Plan 015 baseline>..HEAD` over the scope shows only in-scope files.

## Done criteria

- [ ] Bridge tests prove only exact live or explicitly historical exact evidence creates parent IDs; heuristics never do.
- [ ] Tests prove missing/outside/symlink/path-reuse/malformed/duplicate/cycle cases remain safe and do not break listing.
- [ ] Core tests prove old-cache compatibility, bounded traversal and deterministic family order.
- [ ] UI tests prove accessible lineage/workspace presentation without raw path/ID rendering.
- [ ] Existing active tree/fork/cursor tests remain unchanged and green.
- [ ] Complete non-device commands exit 0; status shows only in-scope changes.

## STOP conditions

Stop if:

- Parentage would require filename/cwd/timestamp/title/prompt heuristics.
- Historical evidence cannot distinguish missing parent from live path reuse.
- Lineage work requires changing active tree authority, locks, cursor synchronization or session files.
- A verification command fails twice after a reasonable correction.
