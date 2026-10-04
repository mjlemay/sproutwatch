package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest.FakeHost;
import org.junit.jupiter.api.Test;

import static dev.hytalemodding.sproutwatch.ui.SproutwatchActionsYouTubeTest.KEY;
import static org.junit.jupiter.api.Assertions.*;

/** Task 9: the Details "YouTube quota" row, the Connect tab key placeholder, and the youtube / twitch commands. */
class YouTubeSettingsUiTest {

    private static FakeHost youTubeReady() {
        FakeHost h = new FakeHost();
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeHandle("@Streamer");
        h.cfg.setYouTubeApiKey(KEY);
        return h;
    }

    private static int index(String id) {
        return StatusSnapshot.DETAIL_IDS.indexOf(id);
    }

    // ---- Details: YouTube quota row ----------------------------------------------------------

    @Test void quotaRowIsOffWhileYouTubeIsDisabled() {
        FakeHost h = new FakeHost();
        h.cfg.setYouTubeQuota("2026-10-03", 1234);
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("YouTube off", s.youTubeQuotaValue());
        assertEquals("YouTube off", s.detailValues().get(index("#YouTubeQuotaValue")));
    }

    @Test void quotaRowShowsUsageWithThousandsSeparatorAndResetTime() {
        FakeHost h = youTubeReady();
        h.cfg.setYouTubeQuota("2026-10-03", 1234);
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("1,234 / 9,800 used today (resets 00:00)", s.youTubeQuotaValue());
        assertEquals("1,234 / 9,800 used today (resets 00:00)", s.detailValues().get(index("#YouTubeQuotaValue")));

        h.cfg.setYouTubeQuota("2026-10-03", 7);
        assertEquals("7 / 9,800 used today (resets 00:00)", new SproutwatchActions(h).snapshot().youTubeQuotaValue());

        YouTubeStatus y = new YouTubeStatus(true, true, "@Streamer", "AIza...abcd", null, 12345, 98000, "21:00");
        StatusSnapshot big = new StatusSnapshot("stopped", false, false, "", 0, 0, "!sprout", false, 0, 0,
            0, 30, 0, 60, 300, 120, false, true, false, false, 0, 0, 0, 0, 0, "north", null, "", false, 0, 0, 0, "",
            java.util.Map.of(), y);
        assertEquals("12,345 / 98,000 used today (resets 21:00)", big.youTubeQuotaValue());
    }

    @Test void quotaRowSitsAfterTheTwitchFeedAndTheListsStayAligned() {
        assertEquals(StatusSnapshot.DETAIL_NAMES.size(), StatusSnapshot.DETAIL_IDS.size());
        assertEquals(StatusSnapshot.DETAIL_NAMES.size(), new SproutwatchActions(youTubeReady()).snapshot().detailValues().size());
        assertEquals("YouTube quota", StatusSnapshot.DETAIL_NAMES.get(3));
        assertEquals(3, index("#YouTubeQuotaValue"));
        assertEquals(index("#FeedValue") + 1, index("#YouTubeQuotaValue"));
    }

    // ---- Connect tab: key placeholder --------------------------------------------------------

    @Test void keyPlaceholderShowsOnlyTheMaskedKey() {
        assertEquals("API key", SettingsPanes.keyPlaceholder(YouTubeStatus.OFF));
        FakeHost h = youTubeReady();
        String p = SettingsPanes.keyPlaceholder(h.youTubeStatus());
        assertEquals("AIza...abcd (saved; paste a new key to replace it)", p);
        assertFalse(p.contains(KEY));
        h.cfg.setYouTubeApiKey("shortkey");
        assertEquals("API key (saved; paste a new key to replace it)", SettingsPanes.keyPlaceholder(h.youTubeStatus()));
        h.cfg.setYouTubeApiKey("");
        assertEquals("API key", SettingsPanes.keyPlaceholder(h.youTubeStatus()));
    }

    // ---- /sproutwatch youtube ----------------------------------------------------------------

    @Test void youTubeOnOffAndUsage() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.",
            ChatSourceCommands.youTube(a, "ON"));
        assertTrue(h.cfg.isYouTubeEnabled());
        assertEquals("YouTube chat is off.", ChatSourceCommands.youTube(a, "off"));
        assertFalse(h.cfg.isYouTubeEnabled());
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "key"));
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "maybe"));
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "frobnicate", "x"));
    }

    @Test void youTubeSettingsRouteToTheActions() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube channel set to @Streamer.", ChatSourceCommands.youTube(a, "handle", "@Streamer"));
        assertEquals("@Streamer", h.cfg.getYouTubeHandle());
        assertEquals("YouTube stream set to video dQw4w9WgXcQ.",
            ChatSourceCommands.youTube(a, "Video", "https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", h.cfg.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.",
            ChatSourceCommands.youTube(a, "video", "clear"));
        assertEquals("", h.cfg.getYouTubeVideo());
        assertEquals("YouTube stream length is now 8h (chat reads are paced so the daily quota lasts that long).",
            ChatSourceCommands.youTube(a, "hours", "8"));
        assertEquals(8.0, h.cfg.getYouTubeStreamHours());
    }

    @Test void youTubeKeyReplyIsMasked() {
        FakeHost h = new FakeHost();
        String reply = ChatSourceCommands.youTube(new SproutwatchActions(h), "key", KEY);
        assertEquals("YouTube API key saved (AIza...abcd).", reply);
        assertFalse(reply.contains(KEY));
        assertEquals(KEY, h.cfg.getYouTubeApiKey());
    }

    @Test void youTubeHoursRejectsOutOfRangeAndNonNumbers() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        for (String bad : new String[] {"0", "25", "-3", "six", "", "NaN", "Infinity"}) {
            assertEquals(ChatSourceCommands.HOURS_HINT, ChatSourceCommands.youTube(a, "hours", bad), bad);
        }
        assertEquals(0, h.saveCalls, "a rejected value is not saved");
        assertEquals(ChatSourceCommands.HOURS_HINT, ChatSourceCommands.streamHours(a, null));
        assertTrue(ChatSourceCommands.HOURS_HINT.contains("Set this to your longest stream."));
        assertEquals("YouTube stream length is now 1h (chat reads are paced so the daily quota lasts that long).",
            ChatSourceCommands.streamHours(a, 1.0));
        assertEquals("YouTube stream length is now 24h (chat reads are paced so the daily quota lasts that long).",
            ChatSourceCommands.streamHours(a, 24.0));
    }

    @Test void youTubeHoursAcceptsWholeHoursOnly() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        for (String bad : new String[] {"2.5", "6.0", "1e1", "+6", "006"}) {
            assertEquals(ChatSourceCommands.HOURS_HINT, ChatSourceCommands.youTube(a, "hours", bad), bad);
        }
        assertEquals(0, h.saveCalls);
        assertEquals("YouTube stream length is now 12h (chat reads are paced so the daily quota lasts that long).",
            ChatSourceCommands.youTube(a, "hours", " 12 "));
        assertEquals(12.0, h.cfg.getYouTubeStreamHours());
    }

    @Test void youTubeSummary() {
        FakeHost off = new FakeHost();
        assertEquals("YouTube chat is off. " + ChatSourceCommands.YOUTUBE_USAGE,
            ChatSourceCommands.youTubeSummary(new SproutwatchActions(off).snapshot()));
        FakeHost on = youTubeReady();
        on.cfg.setYouTubeQuota("2026-10-03", 40);
        String s = ChatSourceCommands.youTubeSummary(new SproutwatchActions(on).snapshot());
        assertEquals(String.join("\n", "YouTube: stopped", "YouTube channel: @Streamer", "YouTube key: AIza...abcd",
            "YouTube quota: 40 / 9,800 used today (resets 00:00)"), s);
        assertFalse(s.contains(KEY));
    }

    // ---- /sproutwatch twitch -----------------------------------------------------------------

    @Test void twitchOnOffAndUsage() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Twitch chat is off.", ChatSourceCommands.twitch(a, "off"));
        assertFalse(h.cfg.isTwitchEnabled());
        assertEquals("Twitch chat is on.", ChatSourceCommands.twitch(a, "On"));
        assertTrue(h.cfg.isTwitchEnabled());
        assertEquals(ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitch(a, "sometimes"));
        assertEquals(ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitch(a, null));
        assertEquals("Twitch chat is on. " + ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitchSummary(true));
        assertEquals("Twitch chat is off. " + ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitchSummary(false));
    }
}
