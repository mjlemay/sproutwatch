package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import static dev.hytalemodding.sproutwatch.ui.ChatSourceActionsYouTubeTest.KEY;
import static org.junit.jupiter.api.Assertions.*;

/** Task 9: the Details "YouTube quota" row, the Connect tab key placeholder, and the youtube / twitch commands. */
class YouTubeSettingsUiTest {

    private static FakeHost youTubeReady() {
        FakeHost host = new FakeHost();
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeHandle("@Streamer");
        host.config.setYouTubeApiKey(KEY);
        return host;
    }

    private static int index(String id) {
        return StatusSnapshot.DETAIL_IDS.indexOf(id);
    }

    // ---- Details: YouTube quota row ----------------------------------------------------------

    @Test void quotaRowIsOffWhileYouTubeIsDisabled() {
        FakeHost host = new FakeHost();
        host.config.setYouTubeQuota("2026-10-03", 1234);
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("YouTube off", s.youTubeQuotaValue());
        assertEquals("YouTube off", s.detailValues().get(index("#YouTubeQuotaValue")));
    }

    @Test void quotaRowShowsUsageWithThousandsSeparatorAndResetTime() {
        FakeHost host = youTubeReady();
        host.config.setYouTubeQuota("2026-10-03", 1234);
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("1,234 / 9,800 used today, about 9.5 h left (resets 00:00)", s.youTubeQuotaValue());
        assertEquals("1,234 / 9,800 used today, about 9.5 h left (resets 00:00)", s.detailValues().get(index("#YouTubeQuotaValue")));

        host.config.setYouTubeQuota("2026-10-03", 7);
        assertEquals("7 / 9,800 used today, about 10.9 h left (resets 00:00)", new SproutwatchActions(host).snapshot().youTubeQuotaValue());

        YouTubeStatus y = new YouTubeStatus(true, true, "@Streamer", "AIza...abcd", null, 12345, 98000, "21:00", 2.25);
        StatusSnapshot big = new StatusSnapshot("stopped", false, false, "", 0, 0, "!sprout", false, 0, 0,
            0, 30, 0, 60, 300, 120, false, true, false, false, 0, 0, 0, 0, 0, "north", null, "", false, 0, 0, 0, "",
            java.util.Map.of(), y);
        assertEquals("12,345 / 98,000 used today, about 2.3 h left (resets 21:00)", big.youTubeQuotaValue());
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
        FakeHost host = youTubeReady();
        String p = SettingsPanes.keyPlaceholder(host.youTubeStatus());
        assertEquals("AIza...abcd (saved; paste a new key to replace it)", p);
        assertFalse(p.contains(KEY));
        host.config.setYouTubeApiKey("shortkey");
        assertEquals("API key (saved; paste a new key to replace it)", SettingsPanes.keyPlaceholder(host.youTubeStatus()));
        host.config.setYouTubeApiKey("");
        assertEquals("API key", SettingsPanes.keyPlaceholder(host.youTubeStatus()));
    }

    // ---- /sproutwatch youtube ----------------------------------------------------------------

    @Test void youTubeOnOffAndUsage() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.",
            ChatSourceCommands.youTube(a, "ON"));
        assertTrue(host.config.isYouTubeEnabled());
        assertEquals("YouTube chat is off.", ChatSourceCommands.youTube(a, "off"));
        assertFalse(host.config.isYouTubeEnabled());
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "key"));
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "maybe"));
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "frobnicate", "x"));
    }

    @Test void youTubeSettingsRouteToTheActions() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("YouTube channel set to @Streamer.", ChatSourceCommands.youTube(a, "handle", "@Streamer"));
        assertEquals("@Streamer", host.config.getYouTubeHandle());
        assertEquals("YouTube stream set to video dQw4w9WgXcQ.",
            ChatSourceCommands.youTube(a, "Video", "https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", host.config.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.",
            ChatSourceCommands.youTube(a, "video", "clear"));
        assertEquals("", host.config.getYouTubeVideo());
        assertEquals("YouTube chat is checked every 30s (about 16.3 hours of chat a day).",
            ChatSourceCommands.youTube(a, "every", "30"));
        assertEquals(30, host.config.getYouTubeCheckSeconds());
    }

    @Test void youTubeKeyReplyIsMasked() {
        FakeHost host = new FakeHost();
        String reply = ChatSourceCommands.youTube(new SproutwatchActions(host), "key", KEY);
        assertEquals("YouTube API key saved (AIza...abcd).", reply);
        assertFalse(reply.contains(KEY));
        assertEquals(KEY, host.config.getYouTubeApiKey());
    }

    @Test void youTubeEveryRejectsOutOfRangeAndNonNumbers() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        for (String bad : new String[] {"0", "9", "301", "-3", "twenty", "", "20.5", "20s", "+20", "1e2", "NaN"}) {
            assertEquals(ChatSourceCommands.CHECK_HINT, ChatSourceCommands.youTube(a, "every", bad), bad);
        }
        assertEquals(0, host.saveCalls, "a rejected value is not saved");
        assertEquals(ChatSourceCommands.CHECK_HINT, ChatSourceCommands.checkSeconds(a, null));
        assertTrue(ChatSourceCommands.CHECK_HINT.contains("(10 to 300)"));
        assertEquals("YouTube chat is checked every 10s (about 5.4 hours of chat a day).", ChatSourceCommands.checkSeconds(a, 10));
        assertEquals("YouTube chat is checked every 300s (enough for the whole day).", ChatSourceCommands.youTube(a, "every", " 300 "));
        assertEquals(300, host.config.getYouTubeCheckSeconds());
    }

    @Test void youTubeHoursIsRetired() {
        SproutwatchActions a = new SproutwatchActions(new FakeHost());
        assertEquals(ChatSourceCommands.YOUTUBE_USAGE, ChatSourceCommands.youTube(a, "hours", "8"));
        assertTrue(ChatSourceCommands.YOUTUBE_USAGE.contains("every <10-300>"));
    }

    @Test void checkHintShowsTheEstimate() {
        assertEquals("About 10.9 hours of chat a day on the free quota.",
            SettingsPanes.youTubeCheckHint(20));
        assertTrue(SettingsPanes.youTubeCheckHint(120).startsWith("Enough for the whole day"));
    }

    @Test void youTubeSummary() {
        FakeHost off = new FakeHost();
        assertEquals("YouTube chat is off. " + ChatSourceCommands.YOUTUBE_USAGE,
            ChatSourceCommands.youTubeSummary(new SproutwatchActions(off).snapshot()));
        FakeHost on = youTubeReady();
        on.config.setYouTubeQuota("2026-10-03", 40);
        String s = ChatSourceCommands.youTubeSummary(new SproutwatchActions(on).snapshot());
        assertEquals(String.join("\n", "YouTube: stopped", "YouTube channel: @Streamer", "YouTube key: AIza...abcd",
            "YouTube quota: 40 / 9,800 used today, about 10.8 h left (resets 00:00)"), s);
        assertFalse(s.contains(KEY));
    }

    // ---- /sproutwatch twitch -----------------------------------------------------------------

    @Test void twitchOnOffAndUsage() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Twitch chat is off.", ChatSourceCommands.twitch(a, "off"));
        assertFalse(host.config.isTwitchEnabled());
        assertEquals("Twitch chat is on.", ChatSourceCommands.twitch(a, "On"));
        assertTrue(host.config.isTwitchEnabled());
        assertEquals(ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitch(a, "sometimes"));
        assertEquals(ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitch(a, null));
        assertEquals("Twitch chat is on. " + ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitchSummary(true));
        assertEquals("Twitch chat is off. " + ChatSourceCommands.TWITCH_USAGE, ChatSourceCommands.twitchSummary(false));
    }
}
