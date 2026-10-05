# Sproutwatch Audit Cleanup: Backlog and Execution Guide

Written 2026-10-04 after the live-stream code walkthrough (Segments 1-8 plus the ECS side segment).
Status: AWAITING MERTIE'S REVIEW. Nothing below has been started.

Purpose: one place listing every change the audit turned up, ranked by tier, with enough detail
that work can resume after a break without the walkthrough transcript. Each item says why,
where, what, and how to verify. When a tier is approved, write a detailed per-tier plan (the
writing-plans format: exact code, failing test first) before touching code.

---

## 0. Read this first (standing rules)

These come from Mertie and apply to every change in this backlog:

- No commits or pushes without Mertie's explicit go-ahead. Checkpoint with `git add -A` only.
- No shorthand in function names or primary variables (fields, parameters, meaningful locals).
  `acknowledged`, not `acked`. Single letters are fine only for loop counters and loop element
  variables. Established acronyms (API, ID, UI, URL, HTTP) are fine.
- American spelling everywhere: code, comments, docs, user-facing strings.
- No HTML in comments or Javadoc (no `<p>`, `<ul>`, `<li>`, `<em>`, `&lt;`). Plain text, blank
  comment line for paragraphs, "- " for lists. `{@link}`, `{@code}` and `@param` are allowed.
- Never use Mertie's last name in code, comments, tests, examples or docs. The only allowed
  occurrence is the author email in `src/main/resources/manifest.json` (open question below).
- Never open, print or commit `.sproutwatch-yt-key`.
- `.ui` strings stay ASCII; no `&`. A `.ui` parse error or a wrong property type disconnects
  the player at join. A TextField whose PlaceholderText is an inline literal takes a plain String
  from `cmd.set(...)`, never a `Message`.
- Engine API names are not ours to rename (for example `Damage.setCancelled`, `isCancelled`,
  `getReferenceTo`). Only rename our own identifiers.

Baseline before starting: 532 tests passing, `scripts/check_ui.py` clean, deployed build matches
the staged tree, zero commits on `dev`.

---

## 1. Prerequisite: commit decision (Mertie)

Recommended: commit the current staged state as the initial commit BEFORE any cleanup, so each
tier lands as its own reviewable commit. Ask Mertie; do not commit on your own.

Open question for Mertie: keep, change or blank the author email in
`src/main/resources/manifest.json` before the mod goes public.

---

## 2. Recommended order

1. Tier 3 (readability): mechanical, no behavior change, fully covered by the existing tests.
2. Tier 1 (bug prevention): one item at a time, each with its own new tests.
3. Tier 2 (structure): moves code that Tier 1 touches, so it goes after.
4. Tier 4 (enforcement): last; it builds on Tier 2's seams.

Per item workflow (same as the YouTube plan): implementer subagent, spec review, code-quality
review, fixes, re-review. After each tier: full test suite, `scripts/check_ui.py`, `./deploy.sh`,
headless boot check (see memory reference_hytale_headless_server), then report to Mertie.

---

## Tier 3: Readability (do first)

### 3.1 Method renames
Why: no-shorthand rule; one is Mertie's own example.

| Current | New | Where |
|---|---|---|
| `feedAcked()` | `feedAcknowledged()` | `ui/ActionsHost.java`, `SproutwatchPlugin.java`, `ui/StatusSnapshot.java` callers, `SproutwatchActionsTest.FakeHost` |
| `isMembershipAcked()`, field `membershipAcked` | `isMembershipAcknowledged()`, `membershipAcknowledged` | `twitch/TwitchMembershipClient.java` and callers |
| `findByRef()` | `findByReference()` | `pen/PenRegistry.java`, `pen/PenGuardSystem.java` |
| `removeByRef()` | `removeByReference()` | `pen/PenRegistry.java`, `pen/PenDespawnSystem.java`, `pen/SproutDeathSystem.java` |
| field `byRef` | `byReference` | `pen/PenRegistry.java` |
| `lookUpYouTubeChannel(String handle)` | `lookUpViewerChannelId(String viewerHandle)` | `ui/ActionsHost.java`, `SproutwatchPlugin.java`, `ui/SproutwatchActions.java`, FakeHost |
| `lookUp(...)` in actions | `lookUpViewer(...)` | `ui/SproutwatchActions.java` |
| `enc()` | `urlEncode()` | `youtube/YouTubeApi.java` (moves under a "URL helpers" heading; see 2.4) |
| `doc()` / `str()` / `items()` / `instant()` | `childDocument()` / `stringField()` / `items()` / `parseInstant()` | see 2.4 (they move to `YouTubeJson`) |

Keep `YouTubeApi.channelIdForHandle(handle)` as is: at the API layer it really is generic.

### 3.2 Class and field renames
| Current | New | Notes |
|---|---|---|
| `youtube/YtException` | `YouTubeException` | 6 files use it |
| `youtube/YtMessage` | `YouTubeMessage` | 4 files use it |
| fields `ytApi`, `ytApiKey`, `ytPacer`, `ytLookups`, `ytState`, `ytConfigured` | `youTubeApi`, `youTubeApiKey`, `youTubePacer`, `youTubeLookups`, `youTubeState`, `youTubeConfigured` | plugin, StatusSnapshot, YouTubeStatus |
| `YouTubeChatSource.handle` | `streamerHandle` | clarifies whose handle (vs. viewer lookups) |
| `PenTicker.exec` | `scheduler` | |
| `PenTicker.start()` local `int s` | `tickSeconds` | |
| `HH_MM` constant | `HOUR_MINUTE_FORMAT` | `YouTubeChatSource` |
| `backoffMs`, `INITIAL_BACKOFF_MS`, `MAX_BACKOFF_MS`, `JOIN_MS` etc. | `...Millis` | match `QuotaPacer`'s `MILLIS` convention; both chat clients |
| `gen` (49 uses, YouTubeChatSource) | `generation` param; field becomes `currentGeneration` | avoid shadowing confusion |

### 3.3 "login" means viewer key now
Why: since YouTube, roster keys include `yt:UC...`, which is not a login (275 uses of `login`).
Rename to `viewerKey` in the pen and roster code: `PenRegistry.Entry.login`, `byLogin`,
`retiredLogins()`, `ChatRoster` maps and javadoc, `PenReconciler` comments, `PenTicker` locals,
`SproutSpawner.spawn(world, login)`. Keep "login" where it truly is a Twitch login
(`TwitchMembershipClient`, `MembershipParser`, `SproutwatchConfig.normalizeChannel`, config list
names `AllowUsers` / `IgnoreUsers` stay as saved keys). Config methods `allowedLogins()` /
`ignoredLogins()` become `allowedViewers()` / `ignoredViewers()`.
Large but mechanical; do it as its own step after 3.1 and 3.2 so diffs stay readable.

### 3.4 Variable renames (primary variables only)
Counts are in `src/main` at audit time:
`cfg` -> `config` (94 uses, 10 files), `cmd` -> `commands` (90, 5), `evt` -> `events` (42, 3),
`msg` -> `message` (22, 3), `err` -> `error` (21, 4), `sel` -> `selectors` (23, 3),
`ctx` -> `context` (141, 1: SproutwatchCommand), `op` -> `operation` (12), `req` -> `request`,
`resp` -> `response`, `src` -> `source`, `sep` -> `separator`, `u` -> `universe`, `t` -> `transform`,
`p` -> `position`, `e`/`ex` as exceptions -> `exception`, `e` as map entry outside a loop -> `entry`,
`chair` -> `chairCamera` (SeatedInvulnerabilitySystem), `l` -> `listener` (QuotaPacer.fireUsage).
Switch pattern bindings get full names: `case RosterEvent.Join j` -> `case RosterEvent.Join join`
(ChatRoster.apply). Loop variables (`for (String l : ...)`, `for (... e : registry.snapshot())`) stay.
Watch for collisions: in `SproutwatchSettingsPage.build` the parameter `cmd` becomes `commands`
but a field or local may already be named `config`; check each method before renaming.

### 3.5 Comment cleanup
- HTML tags (49 at audit time). Files: `youtube/YouTubeChatSource.java` (12), `QuotaPacer.java` (8),
  `YouTubeApi.java` (8), `YouTubeRef.java` (8), `pen/PenRegistry.java` (5), `ui/ActionsHost.java` (4),
  `ui/YouTubeStatus.java` (4), `pen/SproutDeathSystem.java` (3), `chat/ChatSource.java` (2),
  `commands/SproutwatchCommand.java` (2), `config/SproutwatchConfig.java` (1), `youtube/QuotaStore.java` (1),
  `youtube/YtException.java` (1).
  Find them: `grep -rnE '<(p|ul|li|em|b|br|pre|i|/ul|/li|/em|/b|/i)>|&lt;|&gt;' src`
- British spellings (comments, docs and our identifiers): centre/centred/Centre, colour(s),
  behaviour, grey, cancelled (our comment in PenGuardSystem only; the engine method stays),
  authorisation, honour(ing/s). Identifiers to rename: `PenPlacer.centreLocalX` / `centreLocalZ`
  -> `centerLocalX` / `centerLocalZ`. Also the YouTube spec doc. Also check `src/test` (about 10 hits).
  Find them: `grep -rniE '\b(centre|centred|colours?|behaviour|grey|cancelled|authoris|honour)' src docs`
  ("analysis" / "analyses" are the same in American English; not a hit.)
- Out-of-date comments:
  - `ChatRoster` class Javadoc: "Written from the Twitch client thread" and "lowercase logins"
    (now two source threads; YouTube keys are case-sensitive).
  - `SproutQueue` Javadoc: "Written from the Twitch client thread", "lowercase logins".
  - `PenRegistry` line ~15: "no listeners in v1".
  - "Copy of Subinator's ..." in `PenDespawnSystem`, `SproutDeathSystem` (and any other
    "Subinator" mention): replace with the reasoning itself.
  - `StatusSnapshot.maskedKey` Javadoc still shows the old "..." character.
  - `SproutwatchConfig.getYouTubeStreamHours` Javadoc says "6 when unreadable" was fixed to 8;
    re-check no other "6 h" default mentions remain.

Verify Tier 3: no behavior change, so the existing 532 tests must pass unchanged except for
renamed symbols. `grep` checks above return nothing. Deploy and headless boot.

---

## Tier 1: Bug prevention

### 1.1 `enum PageAction` for settings page actions
Why: action names are strings typed in two places (`SettingsPanes` bindings and the switch in
`SproutwatchSettingsPage.handleDataEvent`). A typo compiles and shows "Unknown action" at runtime.
What: `ui/PageAction.java` enum, each value carrying its wire name; `SettingsPanes` binds with
`PageAction.REMOVE_CHANNEL.wireName()`; the dispatcher does `PageAction.fromWire(e.action)` and
switches over the enum (exhaustive switch: a new action without a case fails to compile).
Test: every enum value has a case (compile-time); `fromWire` round-trips every value; unknown
wire name maps to an `UNKNOWN` path with the existing message.

### 1.2 `PageState` record instead of control values hidden in the label list
Why: `statusLabels()` appends three non-label values (listener tone, pen set, run button) to the
end of the label list; `setStatusLabels` reads them as `labels.get(n - 3)` and so on. Adding a
label in the wrong place silently shifts them.
What: `record PageState(List<String> labels, String listenerTone, boolean penSet, String runButton,
String listsKey)`. Change-gating compares `PageState.equals`. Fold `lastSentLabels` and
`lastSentLists` into one `lastSentState`.
Test: a unit test on the pure part (building PageState from a StatusSnapshot), including that a
change in only the run button counts as a change.

### 1.3 `ReconcileRequest` record instead of 10 parameters and 5 overloads
Why: `PenReconciler.reconcile` takes 10 parameters (two adjacent `long`s are easy to swap) and has
a chain of 5 overloads.
What: `record ReconcileRequest(Map<String, Long> roster, Map<String, Long> pen, List<String> queue,
int cap, long graceMillis, long now, boolean persist, Set<String> guests,
Map<String, Long> lastActive, long quietMillis)` with a builder or static factory supplying the
defaults the old overloads supplied. One `reconcile(ReconcileRequest)`. Update `PenTicker` and
`PenReconcilerTest` (33 tests) to build requests.
Test: all 33 existing reconciler tests pass when ported unchanged in meaning.

### 1.4 `enum Endpoint` with path and cost
Why: endpoint paths exist as constants in `YouTubeApi` but are repeated as string literals in
`YouTubeChatSource` (`costOf("liveChat/messages")`, `costOf("search")` ...).
What: `youtube/Endpoint.java`: `CHANNELS("channels", 1)`, `SEARCH("search", 1)`,
`VIDEOS("videos", 1)`, `CHAT_MESSAGES("liveChat/messages", 2)`. `costOf(String)` becomes
`Endpoint.cost()`. Keep the comment explaining the measured costs and the search-cost risk (below).
Test: existing pacer and chat source tests; a test that every endpoint has a positive cost.

### 1.5 One stop pattern for both chat sources
Why: `YouTubeChatSource` uses a generation counter so a late thread can't change state or the
roster after Stop; `TwitchMembershipClient` relies on a `running` flag plus being one-shot.
Join timeouts differ (500 ms vs 1000 ms).
What: extract the generation, state and announce logic into a small shared helper (for example
`chat/SourceLifecycle`) used by both; one `STOP_JOIN_MILLIS` constant. Make the Twitch read loop
check the generation before every roster apply.
Test: a Twitch test where the fake server sends a line after `stop()` and the roster stays empty
(mirrors the existing YouTube late-apply test).

Known risk to watch (not a code change): `search.list` is documented by Google at 100 units, but
Mertie's Cloud Console measurement put the whole startup sequence at 7 units, so the code counts
search as 1. If real streams show bigger jumps on the quota page, set SEARCH's cost to 100; the
200-unit reserve would then cover only two stream starts a day.

---

## Tier 2: Structure

### 2.1 Split `SproutwatchActions` (603 lines, four jobs)
Into `ChatSourceActions` (start/stop/begin, Twitch and YouTube setters and removes, stream hours),
`PenActions` (place, clear, prefab, creatures, max sprouts, interval, persist, auto-start),
`ViewerListActions` (filter mode, allow/ignore add/remove, YouTube lookups, `lastLookupMessage`,
the pending-lookup cap). `SproutwatchActions` either disappears or becomes a thin holder of the
three. Page and commands call the one they need. Split the test classes to match.

### 2.2 Extract `YouTubeService` from the plugin
Owns the shared `YouTubeApi` client (create, reuse, close off-thread), the lookup executor,
`lookUpViewerChannelId`, quota store attachment and the running pacer. The plugin keeps start/stop
orchestration. This also moves the `synchronized (this)` inside `closeYouTubeApi` to the service's
own private lock.

### 2.3 Move the roster to `chat/` and merge presence maps
Move `twitch/ChatRoster`, `twitch/SproutQueue`, `twitch/RosterEvent` to `chat/`. Inside the roster,
replace `firstSeen` + `lastActive` with one `ConcurrentHashMap<String, Presence>` where
`record Presence(long firstSeen, long lastActive)`, updated with `compute` so both change together.

### 2.4 `youtube/YouTubeJson.java` for the duplicated JSON helpers
`doc` and `str` are identical in `YouTubeApi` and `YouTubeChatParser`. New package-private final
class with `childDocument`, `stringField`, `items`, `parseInstant`; delete the copies. `enc`
stays in `YouTubeApi` as `urlEncode` under a "URL helpers" heading (it is not JSON).
Promote to a project-wide package only if a second package ever needs it.

### 2.5 Split seated-player state out of `ChairCameraService`
`ChairCameraService` (215 lines) is an ECS system, the camera controller, the HUD hider and the
owner of the seated set. Extract `camera/SeatedPlayers` (the set, `isSeated`) read by both
`ChairCameraService` and `SeatedInvulnerabilitySystem`.
Optional, decide with Mertie: a `SproutTag` marker component added at spawn, so the ECS systems'
queries match only our creatures instead of every entity. Trade-off: a registered custom component
is saved with the entity, so creatures left behind after uninstalling the mod would carry unknown data.

### 2.6 Smaller methods
- `PenTicker.tickOnWorldThread` (85 lines, six jobs): extract `dropPhantomEntries`,
  `eligibleViewers`, `applyPlan`.
- `YouTubeChatSource` (482 lines): consider separate resolve and poll phases.
- `SettingsPanes.connect`: a `youTube(...)` helper if it grows further.

---

## Tier 4: Enforcement and testability

### 4.1 Enforce the locking rule
Rule (SproutwatchPlugin, `sources` Javadoc): status reads, state hooks and roster suppliers must
never take the plugin lock. Replace `synchronized` methods with a private lock object so outside
code cannot take it, and add a test that blocks inside `stopListener` (fake source whose `stop()`
waits on a latch) and asserts `listenerState()`, `sourceStates()` and `youTubeStatus()` still
return from another thread.

### 4.2 Interfaces in front of the engine for the untestable paths
- `WorldExecutor` (isInThread, execute) so the settings page's push handoff to the player's world
  thread gets a unit test (today only an in-game check proves it).
- `PageSink` (sendUpdate, rebuild) for the same tests.
- Optionally one shared `SproutwatchScheduler` for the tick scheduler, YouTube poller and lookup
  executor, giving a single shutdown point.

---

## Follow-ups found during execution

- (found in 1.4) `YouTubeApi.liveVideoId` makes an extra `videos` call to break a tie when several
  streams are live at once; that call is not charged to the quota. Charge `Endpoint.VIDEOS.cost()`
  for it (needs the call count surfaced to `YouTubeChatSource.resolve`, or the API reporting units).
- (found in 1.2) `PageState.runButton` is still the strings "start" / "connecting" / "stop"; an
  enum would match the 1.1 approach.

## 3. Still owed outside this backlog

- Task 11 live test of YouTube chat (checklist in the YouTube plan). Partly done: the page opens
  after the PlaceholderText fix. Not yet confirmed in game: the `*` key masking, YouTube spawns and
  names, `!sprout` from YouTube, Twitch alongside, the Viewers tab lookup line and new rows appearing
  live, the command lookup reply, `grep -c AIza` on the server log is 0.
- The existing world config still has `YouTubeStreamHours` 6; Mertie may run
  `/sproutwatch youtube hours 8`.

---

## 4. Progress log

(Append dated entries here as tiers are approved, started and finished.)

- 2026-10-04: backlog written; awaiting Mertie's review.
- 2026-10-04: Tier 3 pass A (3.1, 3.2, 3.5) done: 532 tests.
- 2026-10-04: Tier 3 pass B (3.3, 3.4) done: 532 tests.
- 2026-10-04: Tier 3 reviewed (identifier-only diff confirmed; only literal change "centred" -> "centered"); review fixes applied (ChatRoster command local, SproutQueue/PenDespawnSystem/PenRegistry/SproutSpawner comments, remaining British spellings incl. test names); 532 tests; deployed; headless boot clean. Waiting for Mertie to commit Tier 3 before Tier 1.
- 2026-10-04: Tier 1.1 PageAction done: 537 tests.
- 2026-10-04: Tier 1.2 PageState done: 545 tests.
- 2026-10-04: Tier 1.3 ReconcileRequest done: 550 tests.
- 2026-10-04: Tier 1.4 Endpoint done: 554 tests.
- 2026-10-04: Tier 1.5 SourceLifecycle done: 563 tests.
- 2026-10-05: Tier 1 complete (1.1-1.5 reviewed; 1.5 review fixes applied, mutation-checked); 563 tests; deployed; headless boot clean. Waiting for Mertie to commit Tier 1 before Tier 2.
- 2026-10-05: Tier 2.4 YouTubeJson done: 577 tests.
- 2026-10-05: Tier 2.3 roster moved to chat/, Presence record: 581 tests.
- 2026-10-05: Tier 2.5 SeatedPlayers done (SproutTag skipped): 586 tests.
- 2026-10-05: Tier 2.2 YouTubeService done: 601 tests.
- 2026-10-05: Tier 2.1 actions split (ChatSourceActions, PenActions, ViewerListActions): 601 tests.
- 2026-10-05: Tier 2.6 smaller methods done: 601 tests.
- 2026-10-05: Tier 2 complete (2.1-2.6; 2.2 reviewed + mutation-checked); 601 tests; deployed; headless boot clean. Waiting for Mertie to commit Tier 2 before Tier 4.
