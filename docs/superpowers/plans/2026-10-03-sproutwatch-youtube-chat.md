# Sproutwatch YouTube Live Chat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** YouTube live-chat participants get pen creatures alongside Twitch viewers (simulcast), per `docs/superpowers/specs/2026-10-03-sproutwatch-youtube-chat-design.md`.

**Architecture:** A `ChatSource` abstraction over the existing Twitch client plus a new polling `YouTubeChatSource` (official Data API v3, streamer's API key) feeding the same `ChatRoster`. YouTube authors are keyed `yt:<channelId>`; a `DisplayNames` map supplies nameplates. Pure pieces (parser, quota pacer, link parsing, identity) are unit-tested; the source is tested against a local fake HTTP server.

**Tech Stack:** Java 25, `java.net.http.HttpClient`, BSON JSON parsing (already on the classpath), JUnit 5, Hytale server API.

**Working state:** branch `dev`, zero commits, everything staged; checkpoints are `git add -A`; never commit without Mertie's go-ahead. Test count at start: 177.

**Order / gates:** Task 1 is a go/no-go spike — if a plain API key cannot read a public live chat, stop and revisit the design with Mertie before Task 2.

---

### Task 1: Spike — prove the API calls with Mertie's key (manual, no code in the mod)

- [ ] **Step 1 (Mertie):** create the key: Google Cloud Console → new project "Sproutwatch" → APIs & Services → Library → enable **YouTube Data API v3** → Credentials → Create credentials → API key → restrict it to YouTube Data API v3. Keep it out of chat; paste it into a local file `~/.sproutwatch-yt-key` (one line) so commands can read it.
- [ ] **Step 2 (Mertie):** start any public test live stream on the channel (unlisted works for the API? — record the answer), or pick a public live stream.
- [ ] **Step 3:** run, recording status codes and the fields present:

```bash
K=$(cat ~/.sproutwatch-yt-key); H=<handle-without-@>
curl -s "https://www.googleapis.com/youtube/v3/channels?part=id&forHandle=@$H&key=$K" | tee /tmp/yt1.json
CH=$(python3 -c "import json;print(json.load(open('/tmp/yt1.json'))['items'][0]['id'])")
curl -s "https://www.googleapis.com/youtube/v3/search?part=id&channelId=$CH&eventType=live&type=video&key=$K" | tee /tmp/yt2.json
V=$(python3 -c "import json;print(json.load(open('/tmp/yt2.json'))['items'][0]['id']['videoId'])")
curl -s "https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=$V&key=$K" | tee /tmp/yt3.json
C=$(python3 -c "import json;print(json.load(open('/tmp/yt3.json'))['items'][0]['liveStreamingDetails']['activeLiveChatId'])")
curl -s "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=$C&part=snippet,authorDetails&key=$K" | tee /tmp/yt4.json | head -c 2000
```

- [ ] **Step 4:** record in a `## Spike results` section at the bottom of this plan: whether call 4 succeeded with a key only (go/no-go), `pollingIntervalMillis` values seen, the exact JSON shape of one message (snippet/authorDetails fields), and the quota used today from Cloud Console (to settle 1 vs 5 units). Save `/tmp/yt4.json` (with author names replaced) as the parser fixture `src/test/resources/youtube/chat_page.json`.

### Task 2: Identity and display names (TDD, pure)

**Files:** Create `src/main/java/dev/hytalemodding/sproutwatch/chat/ViewerKey.java`, `chat/DisplayNames.java`; tests `src/test/java/.../chat/ViewerKeyTest.java`, `DisplayNamesTest.java`. Modify `pen/SproutSpawner.java` (nameplate text), `SproutwatchPlugin.java` (own a `DisplayNames`).

- `ViewerKey.youtube(String channelId)` → `"yt:" + channelId` (channel IDs are case-sensitive: never lowercase); `ViewerKey.isYouTube(key)`; Twitch keys untouched.
- `DisplayNames.put(key, name)`, `nameFor(key)` → name or key; trims, collapses whitespace, caps at 32 chars, strips control characters.
- Tests first: keys never collide with legal Twitch logins (`[a-z0-9_]`), display-name sanitising, fallback to key.
- `SproutSpawner.onSpawned` sets `Nameplate` to `displayNames.nameFor(login)`; existing tests stay green.

### Task 3: Stream reference parsing (TDD, pure)

**Files:** Create `youtube/YouTubeRef.java` + test.

`YouTubeRef.parseHandle("@Name" | "Name" | "https://www.youtube.com/@Name")` → `"@Name"`; `YouTubeRef.parseVideoId(watch URL | youtu.be/ID | /live/ID | bare 11-char ID)` → ID or empty. Tests cover each form and junk input.

### Task 4: Chat page parser (TDD, pure)

**Files:** Create `youtube/YouTubeChatParser.java`, `youtube/YtMessage.java` (record: channelId, displayName, text, publishedAt) + test using the Task 1 fixture.

Parse `items[].authorDetails.channelId`, `.displayName`, `items[].snippet.displayMessage` (or `textMessageDetails.messageText`), `snippet.publishedAt`, plus top-level `nextPageToken`, `pollingIntervalMillis`, `offlineAt` (chat ended). Super chat / sticker items count as a message from the author with their display text. Unknown types are skipped, never thrown.

### Task 5: Quota pacer (TDD, pure)

**Files:** Create `youtube/QuotaPacer.java` + test.

Constructor `(int dailyQuota = 10_000, int reserve = 200, int costPerPoll = 2, double streamHours, ZoneId pacific = America/Los_Angeles)` (2 = measured upper bound, see Spike results). `long nextDelayMillis(long suggestedMillis, Instant now)` = max(suggested, budgetDelay) where budgetDelay = streamHours·3600·1000·costPerPoll / (dailyQuota − reserve − usedToday); `recordCall(int units, Instant now)`; `boolean exhausted(Instant now)`; `Instant resetsAt(Instant now)` (next midnight Pacific); usage resets after it. Tests: 6 h at 2 units → ≥ 4.4 s; honours a larger suggestion; exhausted at budget; resets at midnight Pacific (DST-safe via ZoneId).

### Task 6: `YouTubeApi` HTTP client (TDD against a local fake server)

**Files:** Create `youtube/YouTubeApi.java` + test with `com.sun.net.httpserver.HttpServer` serving canned JSON.

Methods: `channelIdForHandle(handle)`, `liveVideoId(channelId)` (empty when not live), `activeLiveChatId(videoId)`, `chatPage(liveChatId, pageToken)`. Maps HTTP/API errors to a sealed `YtError` (KeyInvalid, QuotaExceeded, ChatEnded, NotFound, Transient). The key is appended as `key=` but every log/exception message redacts it (`key=REDACTED`). Timeouts 10 s.

### Task 7: `ChatSource` + `YouTubeChatSource` (TDD against the fake server)

**Files:** Create `chat/ChatSource.java`; `youtube/YouTubeChatSource.java` + test. Modify `twitch/TwitchMembershipClient.java` to `implements ChatSource` (no behaviour change).

State machine: `finding stream` → (`no live stream found` retry 60 s ×10 → `not live`) → `connected to YouTube (@handle)` while polling → `reconnecting` on transient errors (5 s → 5 min backoff) → `quota exhausted (resets HH:MM)` / `API key rejected` / `chat ended`. First page: remember token only (skip backlog). Each message: `DisplayNames.put` **first**, then `roster.apply(new RosterEvent.Chat(ViewerKey.youtube(id), text), now)` — order matters (Task 2 review): if the roster sees the viewer before the name is stored, a tick can spawn the creature with the raw `yt:UC…` key as its nameplate, and nameplates are set once. Add a test that the name is present at the moment the roster event is applied. `YtMessage.displayName` can be null (parser falls back to `snippet.authorChannelId` when `authorDetails` is missing): `DisplayNames.put(key, null)` removes any entry, so the nameplate falls back to the key — cover it in a test. Map `YtException.Kind` (Task 6): KEY_INVALID → `API key rejected` (stop); QUOTA_EXCEEDED → `quota exhausted (resets HH:MM)` (stop until resetsAt); CHAT_ENDED → `chat ended` (stop); NOT_FOUND during lookup → `no live stream found` (retry 60 s ×10); TRANSIENT → `reconnecting` with backoff; REJECTED (e.g. invalidPageToken) → drop the page token and resume from a fresh first page once (skipping backlog again), then on a second REJECTED stop with state `YouTube rejected the request`. Wrap each loop iteration in `catch (Throwable)` → log + TRANSIENT backoff (Task 4 review). Uses `QuotaPacer` for every sleep; daemon thread named `sproutwatch-youtube`; `stop()` interrupts and joins.
Tests: resolves handle→chat; ignores backlog; applies a later message (roster has `yt:UC…`, display name stored, `!sprout` queued); waits ≥ suggested interval; quota error → state and no further calls; chat ended → stops.

### Task 8: Config, plugin wiring, status

(Task 7 review) Locking rule for the wiring: a source's state-change hook runs on the source's thread, and `stopListener()` stops sources then clears the roster. Never let the hook (statusChanged → OpenPages.refreshAll → snapshot) or the roster's suppliers (ignored logins, queue command) take the plugin monitor that `startListener`/`stopListener` hold — keep `listenerState()`/`listenerRunning()`/`feedAcked()` unsynchronized reads of volatile fields, or the stop join times out (1 s) and a late message could land after the clear.

(Task 5 review) Quota usage must survive restarts: add config keys `YouTubeQuotaDay` (LA date, ISO string) and `YouTubeQuotaUsed` (int); on YouTube start call `pacer.restore(day, used)`; after each API call save `pacer.quotaDay()` / `pacer.usedToday()` (the async config save is fine; at worst one call is lost). Test via FakeHost that a second start the same LA day starts with the saved usage.


**Files:** Modify `config/SproutwatchConfig.java` (+ keys `YouTubeEnabled`, `YouTubeHandle`, `YouTubeVideo`, `YouTubeApiKey`, `YouTubeStreamHours`, `TwitchEnabled`; shipped JSON + `SproutwatchConfigTest` key count), `SproutwatchPlugin.java` (`List<ChatSource>`; start/stop/state/running across sources; Start succeeds if at least one source starts, message lists any that could not), `ui/StatusSnapshot.java` (listener line shows both; `maskedKey()`), `ui/SproutwatchActions.java` (`setYouTubeEnabled`, `setYouTubeHandle`, `setYouTubeKey`, `setYouTubeVideo`), tests for each action and the combined start messages via `FakeHost`.

### Task 9: Settings page and command

**Files:** `SettingsPage.ui` (Connect tab YouTube section: `#YouTubeCheck`, `#YouTubeHandleField`+Save, `#YouTubeKeyField`+Save (shows masked key as placeholder), `#YouTubeVideoField`+Save, hint label), `SettingsPanes.connect`, `SettingsEvent` keys, page actions; Details: Listener row covers both sources, new `YouTube quota` row. `/sproutwatch youtube on|off|handle <h>|key <k>|video <link>`. Run `scripts/check_ui.py`; keep the plan's `.ui` copy in sync.

### Task 10: Allow/ignore with YouTube entries

**Files:** `config/SproutwatchConfig.java` (normalise: keep `yt:` keys case-sensitive), `ui/SproutwatchActions.java` (`@handle` entries resolved once via `YouTubeApi.channelIdForHandle` off-thread, stored as `yt:<id>`; the list rows show `display name (YouTube)`), tests.

### Task 11: Deploy and verify (Mertie)

- [ ] `./deploy.sh`; headless boot clean.
- [ ] In game with a live test stream: enable YouTube, set handle + key, Start → Details shows `YouTube: connected`; a YouTube chat message spawns a creature named with the YouTube display name within one tick; `!sprout` from YouTube queues; Twitch still works at the same time; Stop stops both; key shown masked everywhere; server log never contains the key (`grep -c AIza` on the log = 0).
- [ ] Record results; Mertie decides on commit.

## Spike results (Task 1, 2026-10-03)

Key: stored at the project root as `.sproutwatch-yt-key` for dev spikes only (git-ignored, chmod 600, never in the jar). **The mod never reads this file**: streamers paste their key into the Connect tab / `/sproutwatch youtube key`, saved in the world's config.
Test target: `@LofiGirl` (24/7 public live streams), not Mertie's channel; Mertie's own handle is exercised in Task 11.

1. `channels?part=id,snippet&forHandle=@LofiGirl` -> **200**, `items[0].id` = `UCSJ4gkVC6NrvII8umztf0Ow`.
2. `search?part=id,snippet&channelId=…&eventType=live&type=video` -> **200**, **5 live videos at once**. Design addition: a channel can have several concurrent live streams; auto-detect picks the one with the latest `liveStreamingDetails.actualStartTime` (a streamer's current show), and the pasted-link field overrides it.
3. `videos?part=liveStreamingDetails&id=…` -> **200**; keys `actualStartTime, scheduledStartTime, concurrentViewers, activeLiveChatId`.
4. `liveChat/messages?liveChatId=…&part=snippet,authorDetails` with **API key only -> 200 (GO)**. First page = **57 backlog messages** (confirms skip-backlog). Top level: `kind, etag, pollingIntervalMillis (1901), pageInfo, nextPageToken`. Item: `kind, etag, id, snippet{type: textMessageEvent, liveChatId, authorChannelId, publishedAt, hasDisplayContent, displayMessage, textMessageDetails.messageText}, authorDetails{channelId, channelUrl, displayName, profileImageUrl, isVerified, isChatOwner, isChatSponsor, isChatModerator}`.
5. Next page with `pageToken` after 12 s -> **200**, 0 new items, 0 overlap with page 1, `pollingIntervalMillis` 2056, no `offlineAt` (so `offlineAt` only appears when the chat ends).
- Suggested interval ~2 s confirms budget pacing is mandatory (2 s polling = 43,200 calls/day).
- Quota cost per call: **measured** (Mertie, Cloud Console Quotas page): Queries per day = **0.07% of 10,000 = 7 units** for 1 channels + 1 search + 1 videos + 2 chat reads. Two 5-unit chat reads alone would be 10, so a chat read costs **1 or 2 units** (depending on how search is billed). Pacer uses **costPerPoll = 2** (safety margin): a 6 h stream budget of 9,800 units -> ~4.4 s minimum between reads (YouTube suggests ~2 s, so the pacer, not the suggestion, sets the pace).
- Fixture: `src/test/resources/youtube/chat_page.json` = the real response shape with 5 items, all identifiers/names/text/avatars anonymised (includes `!sprout` and `!SPROUT ` for the queue command). Raw responses deleted.
- Task 3 re-review nits applied inline by the controller: `\p{M}` added to the handle class (`@नमस्ते`, decomposed `é`), host lowercasing uses `Locale.ROOT` (Turkish-locale test). Task 3 approved.
- Task 5 approved after re-review. Applied inline: `restore` keeps max(current, restored) (test `restoreNeverLowersUsage`). Known limits, accepted: (a) streaming far past `YouTubeStreamHours` slows reads sharply (12 s at +0 h → 12 min at +4 h on a 6 h plan) — the Task 9 settings hint must say "set this to your longest stream"; (b) a stream crossing midnight LA spends the new day's budget on the rest of that stream.
- Task 6 review (2026-10-03): fixes requested — body-stall timeout via sendAsync().get(timeout), 2 MiB response cap (BodyHandlers.limiting), 429/408/rateLimitExceeded → TRANSIENT, StackOverflowError caught, `forbidden` → KEY_INVALID only when it mentions an API key, liveVideoId falls back to the first search id if the videos call fails, 3xx → REJECTED, AutoCloseable (one shared instance per plugin). **The API key moves from `key=` to the `X-Goog-Api-Key` header** (verified with the real API: header → 200, no key → 403), keeping it out of URI logging and proxy logs. Ops note for docs: never run the server with `-Djdk.httpclient.HttpClient.log=…` or `-Djdk.internal.httpclient.debug=true` while a key is configured.
- Task 6 approved after re-review (probes: header stall 8006 ms → 2006 ms TRANSIENT; slow-drip 25 s → 2 s; 300 MiB / 1 GiB bodies refused in ~125 ms instead of OOM; 429 → TRANSIENT; deep nesting classified on every path). Ops note (final): with the key in the X-Goog-Api-Key header, plain URI logging is clean, but `-Djdk.httpclient.HttpClient.log=headers|all` would still print it — never enable JDK HttpClient header logging while a key is configured (put this in the Task 9 hint / docs).
- Task 7 approved after re-review (late-apply reproduction now leaves the roster empty; stop during an in-flight call 2 ms; 300 start/stop cycles clean; 27 tests stable over 10 reruns). Warning wording aligned with the Javadoc inline.
- Task 8 approved after re-review; the all-sources-failed fallback now reads "No chat source could start (see the server log)." (applied inline).
