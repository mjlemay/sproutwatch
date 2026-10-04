# Sproutwatch YouTube live chat: design

Date: 2026-10-03. Status: decisions taken in conversation (Mertie), awaiting written-spec review.

## 1. Goal

YouTube live-chat participants get pen creatures exactly like Twitch viewers, **at the same time** as Twitch (simulcast): one pen, one roster, one `!sprout` queue, one allow/ignore filter. The streamer enters their YouTube **@handle** once; on Start the mod finds the current live stream by itself.

## 2. Decisions (Mertie, 2026-10-03)

| Question | Choice |
|---|---|
| Platforms | Both at once; each source on/off independently |
| Finding the stream | @handle once, auto-detect the live video on Start; paste-a-link fallback |
| Access | Official YouTube Data API v3 with the streamer's own API key |

## 3. Non-goals

OAuth / posting to chat / moderation, super chats or memberships as special events, YouTube Shorts or VOD replay chat, the unofficial no-key endpoint, viewer-count–based presence. Twitch behavior is unchanged.

## 4. Facts this design rests on (to be re-verified in Task 1)

- `liveChatMessages.list?liveChatId=…&part=snippet,authorDetails[&pageToken=…]` returns messages, `nextPageToken` and `pollingIntervalMillis`. Google: an API key is required unless an OAuth token is supplied; reading a public chat needs no user authorization. **Task 1 proves this with a real call before anything else is built.**
- Quota: 10,000 units/day per project. Google's cost table currently lists `liveChatMessages.list` at 1 unit (some sources say 5); `videos.list` 1 unit; `channels.list` 1 unit; `search.list` is billed from a separate small daily allowance. Measured 2026-10-03: a chat read costs 1–2 units (7 units for 1 channels + 1 search + 1 videos + 2 chat reads); the design budgets **2 units** per read.
- `channels.list?forHandle=@handle&part=id` → channel ID. `search.list?channelId=…&eventType=live&type=video&part=id` → current live video ID. `videos.list?id=…&part=liveStreamingDetails` → `activeLiveChatId`.
- YouTube has no join/part/member-list feed: only chatters are visible and nobody "leaves".

## 5. Design

### 5.1 Identity

Every roster key gets a platform namespace only for YouTube: Twitch keeps bare lowercase logins (no migration, allow/ignore lists unchanged); YouTube authors become `yt:<channelId>` (stable, never collides with a Twitch login because `:` is not legal in Twitch logins). A new `DisplayNames` map (key → display name, latest seen wins) feeds the nameplate: `SproutSpawner.onSpawned` sets `displayNames.get(login)`, falling back to the key. Twitch logins map to themselves.

Allow/ignore entries accept either form: a Twitch login, or `yt:<channelId>` / `@handle` (a handle entry is resolved to `yt:<channelId>` once via `channels.list` and stored resolved; the page shows the display name next to it).

### 5.2 Components

- **`chat/ChatSource`** (interface): `start()`, `stop()`, `isRunning()`, `getState()`, `setOnStateChange(Runnable)`. `TwitchMembershipClient` implements it unchanged in behavior.
- **`youtube/YouTubeApi`**: thin HTTP client (`java.net.http.HttpClient`, JSON via the BSON parser already on the classpath) with four calls: `channelIdForHandle`, `liveVideoId`, `activeLiveChatId`, `chatPage(liveChatId, pageToken)` → `ChatPage(messages, nextPageToken, pollingIntervalMillis)`. Base URL injectable for tests. The API key is sent in the `X-Goog-Api-Key` header (not the URL), never logged, and redacted from any error text.
- **`youtube/YouTubeChatParser`** (pure): JSON → `List<YtMessage(channelId, displayName, text, publishedAt)>`; ignores non-text event types (super chats still count as a chat message from the author).
- **`youtube/QuotaPacer`** (pure): next delay = max(`pollingIntervalMillis`, budget delay). Budget delay spreads `DailyQuota − reserve` over the configured expected stream length (`YouTubeStreamHours`, default 8; set by command only) at 2 units per call (measured upper bound); resets at midnight Pacific. When the budget is exhausted the source enters state `quota exhausted (resets <time>)` and stops polling.
- **`youtube/YouTubeChatSource`** (implements ChatSource): on start resolves handle → channel → live video → chat ID (states `finding stream`, `no live stream found`), then polls with `QuotaPacer`, applying `RosterEvent.Chat("yt:"+channelId, text)` to the shared `ChatRoster` and updating `DisplayNames`. On the first page it **skips backlog** (only remembers the page token) so a restart does not flood the queue with old `!sprout`s. Errors back off exponentially (5 s → 5 min) like the Twitch client; `liveChatEnded` / 403 `liveChatDisabled` → state `chat ended` and stop.
- **Plugin**: holds a `List<ChatSource>`; `startListener()` starts each enabled source (Twitch if a channel is set and `TwitchEnabled`, YouTube if a handle + key are set and `YouTubeEnabled`); listener state = per-source states joined; running = any source running. The roster is shared; `stopListener()` stops all, then clears it (unchanged order).

### 5.3 Config (new keys)

`YouTubeEnabled` (false), `YouTubeHandle` (""), `YouTubeVideo` ("" — optional pasted link/ID, overrides auto-detect), `YouTubeApiKey` (""), `YouTubeStreamHours` (8), `TwitchEnabled` (true). The key is stored in the world's config file in plain text; the page and `/sproutwatch status` show it masked (`AIza…abcd`).

### 5.4 Settings page

Connect tab gains a **YouTube** section under Channel: Enabled checkbox, @handle field, API key field (masked on display, Save replaces), optional stream link field, and a small "how to get a key" hint. Details tab: the Listener row shows both sources (`Twitch: connected · YouTube: polling every 8s`), with the existing color rules applied to the worst state; a `YouTube quota` row shows units used today / budget.

### 5.5 Presence on YouTube

YouTube viewers are "present" from their first message until Stop; turnover relies on the existing queue-only quiet timeout (QuietSeconds) and persist. No new rule.

## 6. Error handling

| Situation | Behavior |
|---|---|
| Missing key or handle with YouTube enabled | Start refuses with a message naming the missing field; Twitch still starts |
| Handle not found / not live | State `no live stream found`; retries detection every 60 s up to 10 min, then `not live` and idle |
| Key rejected (400/403 keyInvalid) | State `API key rejected`; stop the YouTube source only |
| Quota exceeded (403 quotaExceeded) or pacer budget spent | State `quota exhausted (resets HH:MM)`; stop polling until reset |
| Network error / 5xx | Exponential backoff 5 s → 5 min, state `reconnecting` (red, existing color rule) |
| Chat ended | State `chat ended`; source stops |

## 7. Testing

Pure units with no network: parser (fixture JSON copied from the API docs' example response), quota pacer (budget, reset at midnight Pacific, honoring `pollingIntervalMillis`), handle/link parsing (`@name`, channel URL, watch URL, youtu.be, bare video ID), identity/display-name mapping, allow/ignore `yt:` entries. `YouTubeChatSource` against a local fake HTTP server (same approach as `TwitchMembershipClientTest`): resolves, skips backlog, applies new messages, honors the interval, handles quota/ended errors. Task 1 is a manual spike against the real API with Mertie's key.
