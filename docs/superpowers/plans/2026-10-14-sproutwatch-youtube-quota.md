# Sproutwatch YouTube Quota: Make the Daily Allotment Last a Stream

Written 2026-10-14 after Mertie reported the YouTube API quota running out "ridiculously fast".
Status: IMPLEMENTED 2026-10-14 (20 s default, YouTubeStreamHours retired). Not yet tested on a live stream.

Standing rules from the 2026-10-04 audit plan still apply (no shorthand names, American spelling,
plain-text Javadoc, `.ui` strings ASCII, never open `.sproutwatch-yt-key`, commit only when asked).

---

## 1. Why it runs out

The free YouTube Data API quota is 10,000 units a day per Google Cloud project (resets at
midnight Pacific). `QuotaPacer` keeps 200 back for stream lookups and spreads the other 9,800 over
`YouTubeStreamHours` (default 8).

The pacer thinks one chat read costs 2 units (`Endpoint.CHAT_MESSAGES`). Google's published cost
table (developers.google.com/youtube/v3/determine_quota_cost) lists `liveChatMessages.list` at
**5 units**. The 2026-10-03 measurement that set it to 2 came from the Cloud Console quota page,
which lags and rounds, so it under-read.

Effect with the defaults:

| | Pacer's belief | Reality |
|---|---|---|
| Cost per chat read | 2 | 5 |
| Reads it plans for 8 h | 4,900 (one every ~5.9 s) | same reads |
| Units actually spent | 9,800 | 24,500 |
| When Google cuts it off | never | about 3 h 12 min in |

The settings page quota row under-reports by the same factor, so it showed roughly 40% used when
Google returned `quotaExceeded`.

Other things that make it worse (smaller):
- The quota is per Google Cloud project (per API key), but usage is saved per world config. Two
  worlds, or two PCs, on the same key each think they have the whole 9,800.
- The only pacing control (`YouTubeStreamHours`) is hidden behind `/sproutwatch youtube hours`;
  there is nothing on the settings page.

---

## 2. Changes (in order)

### 2.1 Charge the real cost (the actual bug)
- `youtube/Endpoint.java`: `CHAT_MESSAGES` cost 2 -> 5. Rewrite the class comment to cite Google's
  table instead of the console measurement. `SEARCH`: Google now documents search as a separate
  100-calls-a-day allowance charged 1 unit each, so leave it at 1 and say so.
- `QuotaPacer.COST_PER_POLL` follows automatically.
- Tests: `QuotaPacerTest` / `YouTubeChatSourceTest` expectations that assume 2 units per read.

On its own this makes an 8 h plan read every ~14.7 s and actually last the 8 h.

### 2.2 Separate "check YouTube every N seconds" field (Mertie's idea)
The chat check gets its own interval, independent of the pen's tick timer (`TickSeconds`) and of
the old stream-hours guess.

- Config: new `YouTubeCheckSeconds` (int, default **20**, clamped 10..300). Codec key, getter,
  setter, `Sproutwatch_config.defaults.json`, key-count test.
- Pacer: delay = the largest of (check seconds, YouTube's `pollingIntervalMillis`, 1 s). Keep one
  safety net from the current design: if what is left today would not last 1 more hour at that
  rate, slow down to stretch it to 1 hour (so a long stream degrades instead of going dark).
  Never sleep past the midnight-Pacific refill (unchanged).
- Retire `YouTubeStreamHours`: the codec still reads the key so old configs load, but nothing uses
  it, and `/sproutwatch youtube hours` is replaced by `/sproutwatch youtube every <seconds>`.
  (Alternative if Mertie prefers: keep both, with the interval as a floor. Recommended: retire.)
- Settings page, Connect tab, under the YouTube video field: "Check chat every" TextField
  (seconds) + "Update" button, matching the Listener tab. Under it, a hint label with the
  estimate for that value, e.g. "About 10.9 hours of chat a day at this speed."
- Wiring like the HUD message: `PageAction.SAVE_YOUTUBE_CHECK("saveYouTubeCheck")`,
  `SettingsEvent` key `@YouTubeCheck`, `SettingsPanes` binding, `ChatSourceActions` setter that
  returns the new estimate, `PageActionTest` wire names.
- A running YouTube listener picks up the new interval on its next sleep (no restart needed).

Reference numbers at 5 units a read, 9,800 units a day:

| Check every | Units an hour | Lasts |
|---|---|---|
| 10 s | 1,800 | 5.4 h |
| 15 s | 1,200 | 8.2 h |
| **20 s (default)** | 900 | 10.9 h |
| 30 s | 600 | 16.3 h |
| 60 s | 300 | 24 h (whole day) |

### 2.3 Do not lose messages when checking less often
- `YouTubeApi.chatPage`: add `maxResults=2000` (the API maximum; default is 500). Same 5 units a
  call, and a busy chat cannot overflow one page between checks. Test the query string.

### 2.4 Make the quota row tell the truth
- When Google answers `quotaExceeded`, mark the pacer as spent for the day (used = budget) so the
  row and the saved usage match what Google says, instead of showing headroom that is not there.
- Quota row text adds time left: "4,500 / 9,800 used today, about 5.9 h left (resets 00:00)".
- `/sproutwatch status` quota line gets the same text.

---

## 3. Not doing now (noted for later)

- Sharing the usage counter across worlds that use the same key (would need a file outside the
  world save). Workaround: one key per world, or keep YouTube on in one world at a time.
- Slowing checks when the pen is full and nobody is queued: saves units, but `!sprout` replies and
  presence would lag; revisit only if 2.1-2.2 are not enough.
- Asking Google for a higher quota (free "YouTube API Services - Audit and Quota Extension" form).
  Only worth it if streams regularly run past what 20-30 s checks cover.

---

## 4. Verify

- `./gradlew build`: all tests pass, plus new tests for the 5-unit cost, the interval-based delay,
  the 1-hour safety stretch, the estimate text, `maxResults`, and the quotaExceeded sync.
- `scripts/check_ui.py` clean; open the Connect tab in game, set 30, Update, check the estimate.
- Real stream: after an hour, compare the settings page "used today" with the Cloud Console quota
  page (APIs and Services > YouTube Data API v3 > Quotas). They should agree within a few percent;
  if the console is higher, the per-read cost is still wrong.

## 5. Open questions for Mertie

1. Default interval: 20 s (about 11 h a day) OK? Viewers would wait up to 20 s for their `!sprout`
   to register.
2. Retire `YouTubeStreamHours` (recommended), or keep it alongside the interval?
3. Were two worlds or PCs using the same API key when it ran out? (Section 1, "worse" list.)
