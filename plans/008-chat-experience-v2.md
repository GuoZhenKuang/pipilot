# Plan 008: Deliver a compact, turn-centered mobile chat experience

> **Executor instructions**: Execute this plan from beginning to end, one numbered step at a time. Do not stop after producing another plan. Run each non-device verification gate before moving on, create the listed logical commits, and update this plan plus `plans/README.md` when complete. Do not launch an emulator, run connected tests, install an APK, use adb, or request manual phone testing unless the operator explicitly says **debug mode**.
>
> **Drift check (run first)**: `git diff --stat 4b318ce..HEAD -- app/src/main/java/com/ayagmar/pimobile/chat app/src/main/java/com/ayagmar/pimobile/ui/chat app/src/test/java/com/ayagmar/pimobile/chat app/src/test/java/com/ayagmar/pimobile/ui/chat docs plans`
>
> If any in-scope file changed since this plan was written, compare the current-state excerpts below with live code. If the timeline model, freshness policy, composer callbacks, or navigation structure changed materially, stop and report rather than layering a second implementation over it.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: plans/007-modernize-android-and-release-dx.md
- **Category**: bug, direction, UX, tech-debt
- **Planned at**: commit `4b318ce`, 2026-07-13

## Why this matters

Pi Mobile now has reliable RPC, synchronization, secure onboarding, and QR pairing, but the chat surface still presents implementation details with nearly the same visual weight as the assistant's answer. Large nested cards, full tool output, separate steer/follow-up dialogs, and freshness notifications make the conversation noisy and consume scarce mobile space. This plan makes the answer primary, groups one user request and its activity into a coherent turn, keeps completed tools compact, provides an intentional desktop handoff surface, and reserves sync warnings for actionable conflicts instead of routine freshness changes.

## Product outcome

After this plan:

- A user request and the resulting assistant activity render as one coherent turn.
- The final assistant answer is visually primary and mostly card-free.
- Completed tools collapse into compact, tool-specific activity rows; details remain available on demand.
- Thinking is a quiet disclosure, not a nested high-emphasis card.
- The header is compact and opens a details/handoff sheet.
- The composer remains stable during a run and sends either a steer or follow-up without opening a second text-entry dialog.
- Scrolling away shows a reliable `N new` affordance and never pulls the user away from older content.
- Routine auto-refresh and successful sync produce no notification spam. Only explicit conflicts and failures interrupt the user.

## Current state

### Timeline model

`app/src/main/java/com/ayagmar/pimobile/chat/ChatModelsAndParsing.kt:132-166` models the visible timeline as unrelated flat items:

```kotlin
sealed interface ChatTimelineItem {
    val id: String

    data class User(...) : ChatTimelineItem
    data class Assistant(
        override val id: String,
        val text: String,
        val thinking: String? = null,
        ...
    ) : ChatTimelineItem
    data class Tool(
        override val id: String,
        val toolName: String,
        val output: String,
        ...
    ) : ChatTimelineItem
}
```

Do not replace the event assembly machinery in `ChatViewModel` merely to obtain grouped rendering. Add a pure projection boundary from the existing flat timeline to turn-oriented presentation data. Streaming item IDs, pending optimistic-user reconciliation, entry projection, and bounded history behavior must remain intact.

### Current rendering

`app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatTimeline.kt` renders each flat item as a separate peer row. `AssistantCard`, `ThinkingBlock`, and `ToolCard` all use full-width or nested cards with 12dp padding. Completed tool output is placed directly in the timeline and only truncated by character count. This makes tool plumbing dominate the answer.

The existing useful components must be preserved and reused where appropriate:

- `AssistantMessageContent` and `AssistantCodeBlock` for Markdown/code.
- `DiffViewer` for edit details.
- `ToolArgumentsSection` for explicit argument inspection.
- `ImagePreviewDialog` for sent images.
- Syntax highlighting and selection behavior.

### Current composer

`app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatComposer.kt`:

- disables `PromptInputRow` whenever `isStreaming` is true;
- presents large Stop/Steer/Follow Up controls above the field;
- opens `SteerFollowUpDialog` for text entry;
- renders the complete local queue inspector above the composer.

The controller already exposes distinct `steer`, `followUp`, `abort`, and queue callbacks. Do not invent a new RPC command.

### Current freshness behavior

`app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt:738-853` compares bridge fingerprints every 60 seconds. Any changed fingerprint outside a 90-second local mutation grace window calls `handleSessionFreshnessMismatch`. If the user is streaming or editing, the code sets `sessionCoherencyWarning` and may add a warning notification even when the lock payload does not identify another owner.

`loadInitialMessages` also emits informational notifications for successful manual sync and automatic freshness refresh:

```kotlin
"Session sync complete"
"Session changed externally. Timeline auto-refreshed."
"Session freshness changed. Timeline refreshed."
```

A fingerprint mismatch is evidence that the file changed, not proof of a conflicting external editor. Preserve safety, but distinguish an explicit conflicting lock from a routine freshness change.

### Current scrolling

`ChatTimeline.kt` owns `LazyListState`, a bottom anchor, automatic streaming scroll, and the recently fixed `timelineBottomAnchorIndex`. Preserve that anchor-based approach. The missing UX is a count of activity received while the user is away from the bottom and explicit position preservation when older rows are prepended.

### Architecture constraints

- Preserve Android → authenticated WebSocket bridge → one isolated `pi --mode rpc` process per cwd.
- Keep Pi 0.80.6 compatibility and documented RPC commands.
- Do not alter cursor synchronization, tree navigation, session locks, or bridge protocol for this UI plan.
- Unknown session entries still trigger exactly one explicit rebuild.
- Match Material 3 and the existing Pi theme. Do not add another design system or state-management dependency.
- Keep behavior logic in pure Kotlin projections/reducers where it can be unit tested without Compose or a device.

## Commands you will need

| Purpose | Command | Expected result |
|---|---|---|
| Focused chat tests | `./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.chat.*' --tests 'com.ayagmar.pimobile.ui.chat.*'` | exit 0 |
| App formatting | `./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck` | exit 0 |
| Static analysis | `./gradlew :app:detekt` | exit 0 |
| Debug lint | `./gradlew :app:lintDebug` | exit 0 |
| Android test compilation only | `./gradlew :app:compileDebugAndroidTestKotlin` | exit 0; no device starts |
| Full non-device gate | `./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease` | exit 0 |
| Bridge regression gate | `(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)` | exit 0; no known high production vulnerability |
| Diff hygiene | `git diff --check` | no output |

Do **not** run `connectedDebugAndroidTest`, emulator tasks, `adb`, `installDebug`, or manual APK installation under this plan.

## Suggested executor toolkit

- Read the project `AGENTS.md` before editing.
- Use the existing Compose and Material 3 patterns; do not apply Expo/React Native guidance to this Kotlin project.
- Use pure reducer/projection tests as the primary feedback loop.

## Scope

**In scope**:

- `app/src/main/java/com/ayagmar/pimobile/chat/ChatModelsAndParsing.kt`
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- New focused projection/reducer files under `app/src/main/java/com/ayagmar/pimobile/chat/`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatTimeline.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatComposer.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatStatusAndWidgets.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatAuxiliarySheets.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/DiffViewer.kt` only if needed to expose full-screen/sheet detail content
- Focused tests under `app/src/test/java/com/ayagmar/pimobile/chat/` and `app/src/test/java/com/ayagmar/pimobile/ui/chat/`
- Existing Compose tests only when their compile contracts require updates; do not run them
- `README.md`, `docs/onboarding.md`, `docs/testing.md`, and `plans/README.md` for final behavior/verification updates

**Out of scope**:

- `bridge/`, `core-rpc/`, `core-net/`, and `core-sessions/` behavior changes
- New RPC commands or private session-file assumptions
- Android notifications, offline transcript storage, voice input, and multi-host dashboard
- Dependency upgrades or a new UI framework
- Emulator/device execution, screenshots, APK installation, and manual acceptance
- Rewriting `ChatViewModel` wholesale

## Git workflow

Start from current remote master and create exactly one feature branch:

```bash
git checkout master
git pull --ff-only
git checkout -b feat/chat-experience-v2
```

Before editing, mark Plan 008 `IN PROGRESS` in `plans/README.md` and commit that status with the first logical implementation commit rather than as a planning-only commit.

Use small Conventional Commits commits in this order:

1. `fix(sync): reduce freshness warning noise`
2. `refactor(chat): project timeline into turns`
3. `feat(chat): compact assistant and tool activity`
4. `feat(chat): add session handoff details`
5. `feat(chat): streamline active-run composer`
6. `fix(chat): preserve reading position and unread state`
7. `docs(chat): document chat experience v2`

Do not push or open a PR unless the operator explicitly asks.

## Steps

### Step 1: Make freshness handling quiet and actionable

Change `ChatViewModel` freshness policy without weakening session safety:

1. Add a pure classification function and small data types in a focused file such as `SessionFreshnessPolicy.kt`. Inputs must include:
   - whether the fingerprint changed;
   - whether the current client owns the cwd/session lock;
   - whether a different client explicitly owns either lock;
   - whether chat is busy (streaming, retrying, syncing, draft text, or images);
   - whether the change is inside the local mutation grace window.
2. Required outcomes:
   - unchanged or grace-window change: update baseline only;
   - explicit other-client lock owner: show one persistent actionable conflict state; throttle duplicate notification text;
   - changed fingerprint with no explicit conflicting owner while idle: silently auto-refresh;
   - changed fingerprint with no explicit conflicting owner while busy: defer refresh until the chat becomes idle; do not show a warning or snackbar solely from the fingerprint;
   - refresh/reload failure: show an error state with Sync now recovery.
3. Replace the boolean/string-only warning with a typed presentation state if needed, but keep `ChatUiState` easy to render. Do not include owner client IDs in ordinary UI text.
4. Remove success notifications for manual sync and automatic freshness refresh. A user-triggered sync may use a restrained transient confirmation only if the UI otherwise gives no completion feedback; it must not persist in the timeline or warning area.
5. Clear a stale conflict immediately after a successful sync or when a fresh snapshot proves current-client ownership.
6. Add deterministic tests near `ChatViewModelThinkingExpansionTest` or in a focused `SessionFreshnessPolicyTest` for every classification above, including repeated identical mismatch and random-looking fingerprint changes without another owner.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests '*SessionFreshnessPolicyTest' --tests '*ChatViewModelThinkingExpansionTest'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt
```

Expected: all commands exit 0; no test expects routine `"Session freshness changed"` or `"Session sync complete"` notifications.

Commit: `fix(sync): reduce freshness warning noise`

### Step 2: Add a pure turn projection without disturbing event assembly

1. Create a presentation model in a focused file, e.g. `ChatTurnProjection.kt`:
   - `ChatTurn` with a stable key;
   - optional `User` content;
   - ordered assistant content/activity sections;
   - tool activity collection;
   - run/streaming/error state.
2. Project the existing `List<ChatTimelineItem>` into turns using deterministic rules:
   - each user item starts a new turn;
   - following assistant/tool items belong to that turn until the next user item;
   - history that starts with assistant/tool items forms a stable orphan assistant turn rather than dropping content;
   - preserve exact item ordering inside the turn;
   - never merge across user boundaries;
   - IDs must remain stable as streaming text grows or a tool output updates.
3. Keep the existing flat timeline as `ChatViewModel`'s mutation source for now. The projection belongs at the presentation boundary, avoiding a high-risk rewrite of optimistic reconciliation and stream assembly.
4. Add `ChatTurnProjectionTest` covering:
   - user → thinking assistant → tool → final assistant;
   - multiple tools;
   - consecutive assistant updates represented by current models;
   - orphan history;
   - two user turns;
   - streaming updates retaining the same turn key;
   - tool error retention.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests '*ChatTurnProjectionTest' --tests '*ChatTimelineReducerTest'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt
```

Expected: all tests pass and no `core-*` or bridge file is modified.

Commit: `refactor(chat): project timeline into turns`

### Step 3: Render compact conversation turns and tool activity

Refactor `ChatTimeline.kt` around the turn projection.

1. Visual hierarchy:
   - user content: compact right-aligned bubble, no redundant `You` heading unless accessibility requires a semantics label;
   - assistant answer: plain background content with a small Pi/Assistant label only where turn separation needs it;
   - thinking: one low-emphasis disclosure row (`Thinking`, running/completed state, chevron); content appears only when expanded;
   - tool activity: compact rows grouped under one `N tools used` disclosure;
   - errors: visible compact error row, expanded by default;
   - code/diffs: keep bounded surfaces because containment improves readability.
2. Completed tools must default collapsed. Streaming tool activity may show one live summary line. Do not show hundreds of output characters in the collapsed timeline.
3. Add tool-specific summaries in a pure formatter with tests:
   - `read`: path or readable target;
   - `edit`: changed path and diff availability;
   - `write`: target path;
   - `bash`: command summary plus running/success/error state;
   - unknown tool: tool name and generic status.
   Never expose secrets or fabricate details absent from arguments.
4. Tapping a tool opens or expands a detail surface containing selectable full output, arguments, copy action, and existing diff rendering. Tool details must not cause all other turns to recompose or expand.
5. Preserve image previews, Markdown, syntax highlighting, selection, and accessibility labels.
6. Remove the remaining emoji attachment label in `UserCard`; use existing Material icons and text.
7. Add tests for tool summaries, default collapse, errors, thinking disclosure presentation, and turn rendering inputs. Compose tests may be written but only compile them under this plan.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ayagmar.pimobile.ui.chat.*'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt :app:lintDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: all commands exit 0 and no emulator/device starts.

Commit: `feat(chat): compact assistant and tool activity`

### Step 4: Build a compact header and intentional handoff sheet

1. Reduce `ChatHeader` to two compact lines at most:
   - line 1: session title, connection indicator, overflow;
   - line 2: compact model, thinking, and context controls, horizontally scrollable if needed rather than wrapping vertically.
2. Move stats, session path/cwd, pending count, sync, compact, model details, and secondary actions into a Material 3 modal bottom sheet in `ChatAuxiliarySheets.kt`.
3. Add a **Handoff to computer** section to that sheet using data already available through `ChatUiState`/session stats:
   - session name;
   - cwd and session path when available;
   - connection/run status;
   - model;
   - actions for Copy latest response and Export conversation/session using existing callbacks.
4. Add one explicit `Copy handoff summary` action. The generated summary may contain session name, cwd, session path, model, and whether Pi is working/waiting/idle. Do not include bridge host, auth token, raw lock owner IDs, hidden thinking, or invent an undocumented Pi resume command.
5. Put summary formatting in a pure function and test missing fields, active run, idle run, and sensitive-field exclusion.
6. Keep tree, bash, stats, compact, model selection, and copy actions discoverable in the sheet/overflow.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests '*Handoff*' --tests 'com.ayagmar.pimobile.ui.chat.*'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt :app:lintDebug
```

Expected: all commands exit 0; no token/authorization field exists in handoff models or test fixtures.

Commit: `feat(chat): add session handoff details`

### Step 5: Replace streaming dialogs with a stable active-run composer

1. Keep one composer field in place before, during, and after streaming. It must not move vertically when run state changes.
2. While idle, Send uses the existing normal prompt callback.
3. While streaming, the composer remains text-enabled and displays a compact delivery selector with `Follow up` as the default and `Steer` as the alternative:
   - Follow up invokes existing `callbacks.onFollowUp(text)`;
   - Steer invokes existing `callbacks.onSteer(text)`;
   - clear only the submitted draft after successful local dispatch;
   - image attachment remains disabled for steer/follow-up because the existing callbacks accept text only.
4. Replace the large streaming controls block with:
   - one compact Stop action;
   - a small run state/elapsed indicator;
   - a queue-count chip opening a bottom sheet.
5. Move the full pending queue inspector into a sheet. Keep remove/clear behavior and distinguish steer from follow-up.
6. Remove `SteerFollowUpDialog` once no caller remains. Do not maintain duplicate text-entry paths.
7. Add pure reducer tests for mode switching, draft preservation, submit clearing, empty-submit no-op, queue count, and retry Stop behavior.
8. Update existing `PromptControlsTransitionTest` source to reflect stable controls, but only compile it.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests '*Composer*' --tests '*ChatViewModelThinkingExpansionTest'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt :app:lintDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: all commands exit 0; `rg -n 'SteerFollowUpDialog' app/src/main` returns no matches.

Commit: `feat(chat): streamline active-run composer`

### Step 6: Preserve reading position and show unread activity

1. Extend the existing anchor-based auto-scroll policy rather than replacing it.
2. While near bottom, new streaming activity continues to follow the bottom.
3. Once the user intentionally scrolls away:
   - never auto-scroll for streaming updates;
   - count new turn/activity updates since leaving the bottom;
   - show a compact floating `↓ N new` control;
   - tapping it scrolls to the actual bottom anchor, resets the count, and restores sticky-bottom behavior.
4. When `Load older messages` prepends rows, preserve the previously visible item and offset. Do not jump to the top or bottom.
5. Expansion/collapse of a tool or thinking disclosure must not incorrectly count as a new assistant event.
6. Keep `timelineBottomAnchorIndex` regression coverage and add a pure auto-scroll/unread reducer test. Avoid tests that depend on real time.
7. Add optional reading actions to assistant turns: Copy answer and collapse/expand activity. Do not add a permanent button row to every message; use a compact overflow or long-press semantics.

**Verify**:

```bash
./gradlew :app:testDebugUnitTest --tests '*ChatTimeline*' --tests '*AutoScroll*'
./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt :app:lintDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: all commands exit 0; tests prove optional load-older/progress rows still target the bottom anchor.

Commit: `fix(chat): preserve reading position and unread state`

### Step 7: Document behavior and run all non-device gates

1. Update README chat highlights to describe turn grouping, compact tool activity, active-run composer, unread control, and handoff sheet.
2. Update `docs/testing.md` with focused unit-test commands. State clearly that connected/device validation is pending operator-owned debug mode.
3. Update this plan and `plans/README.md` to `DONE (device acceptance pending — operator debug mode)` only after every non-device gate passes.
4. Run the exact full gates below. Fix failures without weakening lint, deleting meaningful tests, adding broad suppressions, or changing out-of-scope modules.

**Verify**:

```bash
./gradlew clean ktlintCheck detekt test :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
git diff --check
git status --short
```

Expected:

- every command exits 0;
- debug and release APKs assemble, but neither is installed;
- no emulator/device command ran;
- bridge reports no known high production vulnerability;
- only scoped source, tests, docs, and plan status files changed.

Commit: `docs(chat): document chat experience v2`

## Test plan

Add or update focused tests matching the existing JUnit style:

- `SessionFreshnessPolicyTest.kt`: mismatch classification, explicit lock conflict, grace window, busy defer, idle silent refresh, repeated mismatch throttling.
- `ChatTurnProjectionTest.kt`: ordering, stable IDs, orphan activity, multiple turns, streaming updates, errors.
- `ToolActivityPresentationTest.kt`: per-tool summaries, secret-safe fallback, collapse/error status.
- `HandoffSummaryTest.kt`: complete/missing metadata, active state, and exclusion of sensitive/internal fields.
- Composer reducer/ViewModel tests: idle prompt, streaming follow-up, streaming steer, draft preservation, queue count.
- Timeline auto-scroll reducer tests: near-bottom follow, scrolled-away unread count, expansion not counted, jump reset, prepend preservation.
- Update `PromptControlsTransitionTest.kt` and any affected Compose source tests, but do not execute connected tests.

Tests must assert behavior rather than screenshot colors or exact pixel values. Use stable strings/semantics tags only for user-visible contracts.

## Done criteria

- [ ] Work is on `feat/chat-experience-v2`, created from updated `master`.
- [ ] Freshness mismatches without an explicit conflicting owner do not generate warning spam.
- [ ] Explicit conflicting ownership and reload failures remain actionable.
- [ ] Successful automatic refresh is silent.
- [ ] Flat timeline data is projected into stable user/assistant turns without rewriting RPC event assembly.
- [ ] Completed tools default compact and preserve full details on demand.
- [ ] Thinking is a disclosure rather than a nested high-emphasis card.
- [ ] Header occupies at most two compact rows; details and handoff live in a sheet.
- [ ] Handoff output contains no token, authorization data, raw lock owner ID, or fabricated CLI command.
- [ ] Composer remains stable and accepts steer/follow-up text during a run.
- [ ] Scrolled-away users get an accurate unread count and are never forcibly pulled to bottom.
- [ ] Loading older messages preserves reading position.
- [ ] Focused tests, full Android non-device gate, Android-test compilation, bridge gate, and `git diff --check` pass.
- [ ] No emulator, connected test, adb command, APK installation, or manual phone test was run.
- [ ] Seven logical Conventional Commits exist, or fewer only where adjacent steps were inseparable and the commit remains reviewable.
- [ ] `plans/README.md` marks Plan 008 done with device acceptance pending operator debug mode.

## STOP conditions

Stop and report; do not improvise if:

- Turn grouping appears to require changing `core-rpc`, `core-net`, bridge events, session files, or the Pi RPC contract.
- Existing flat timeline ordering cannot unambiguously associate tools with turns. Preserve all content and request a product decision rather than dropping or reordering it.
- A safe handoff appears to require an undocumented Pi resume command. Omit that command and report the gap.
- Supporting image steer/follow-up would require a new RPC shape. Keep images disabled during active-run delivery.
- Reducing warning noise would require ignoring an explicit other-client lock or discarding unsynced content. Preserve the conflict and report.
- A step's focused verification fails twice after a reasonable correction.
- Any requested verification would launch an emulator/device without the operator explicitly enabling debug mode.
- In-scope files materially drift from the excerpts before execution begins.

## Maintenance notes

- Keep the flat timeline mutation path until turn projection proves stable; a future internal model migration can then be planned with characterization coverage.
- Tool summaries must remain data-derived and secret-safe as new Pi tools appear.
- Freshness UI is a presentation policy over existing safety mechanisms; do not remove bridge invalidation, entry cursors, or the 60-second foreground poll.
- Device acceptance remains necessary for keyboard/IME behavior, font scaling, TalkBack order, long streaming, and rotation, but is intentionally deferred until the operator says debug mode.
- Android system notifications, offline history, and multi-host activity are valuable follow-ups after this chat milestone, not scope to smuggle into it.
