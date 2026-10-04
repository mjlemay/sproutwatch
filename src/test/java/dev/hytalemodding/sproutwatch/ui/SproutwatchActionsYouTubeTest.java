package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest.FakeHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** YouTube settings actions, start messages, and the multi-source listener status (Task 8). */
class SproutwatchActionsYouTubeTest {

    static final String KEY = "AIzaTESTKEY0123456789abcd";

    private static Map<String, String> states(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static FakeHost youTubeReady() {
        FakeHost h = new FakeHost();
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeHandle("@Streamer");
        h.cfg.setYouTubeApiKey(KEY);
        return h;
    }

    // ---- start messages ----------------------------------------------------------------------

    @Test void startMessageForEachSourceCombination() {
        FakeHost both = youTubeReady();
        both.cfg.setTwitchChannel("streamer");
        assertEquals("Sproutwatch watching Twitch #streamer and YouTube @Streamer; one sprout every 60s.",
            new SproutwatchActions(both).startListener());

        FakeHost ytOnly = youTubeReady();
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.",
            new SproutwatchActions(ytOnly).startListener());

        FakeHost ytVideo = youTubeReady();
        ytVideo.cfg.setYouTubeVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("Sproutwatch watching YouTube video dQw4w9WgXcQ; one sprout every 60s.",
            new SproutwatchActions(ytVideo).startListener());

        FakeHost twitchOff = youTubeReady();
        twitchOff.cfg.setTwitchChannel("streamer");
        twitchOff.cfg.setTwitchEnabled(false);
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.",
            new SproutwatchActions(twitchOff).startListener());

        FakeHost nothing = new FakeHost();
        nothing.cfg.setTwitchEnabled(false);
        assertEquals("Sproutwatch started, but no chat source started (see the server log).",
            new SproutwatchActions(nothing).startListener());
    }

    @Test void startMessageListsOnlyWhatActuallyStarted() {
        FakeHost h = youTubeReady();
        h.cfg.setTwitchChannel("streamer");
        h.youTubeFails = true;
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube could not start (see the server log).",
            new SproutwatchActions(h).startListener());
    }

    @Test void disablingTheOnlyRunningSourceStopsTheListener() {
        FakeHost tw = new FakeHost();
        tw.refuseLikePlugin = true;
        tw.cfg.setTwitchChannel("streamer");
        SproutwatchActions a = new SproutwatchActions(tw);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", a.startListener());
        assertTrue(tw.running);
        assertEquals("Twitch chat is off. Nothing else is set up, so the listener stopped.", a.setTwitchEnabled(false));
        assertFalse(tw.running, "Twitch must not keep listening");
        assertEquals("Listener: stopped", a.snapshot().listenerLine());

        FakeHost yt = youTubeReady();
        yt.refuseLikePlugin = true;
        SproutwatchActions b = new SproutwatchActions(yt);
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.", b.startListener());
        assertEquals("YouTube chat is off. Nothing else is set up, so the listener stopped.", b.setYouTubeEnabled(false));
        assertFalse(yt.running, "YouTube must not keep listening");
    }

    @Test void aRefusedRestartForAnotherReasonSaysTheListenerStopped() {
        FakeHost h = youTubeReady();
        h.running = true;
        h.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals("YouTube channel set to @bravo. No pen placed yet. Stand where you want it and run /sproutwatch place. The listener stopped.",
            new SproutwatchActions(h).setYouTubeHandle("@bravo"));
        assertFalse(h.running);
    }

    @Test void startMessageNotesAnIncompleteYouTube() {
        FakeHost noKey = new FakeHost();
        noKey.cfg.setTwitchChannel("streamer");
        noKey.cfg.setYouTubeEnabled(true);
        noKey.cfg.setYouTubeHandle("@Streamer");
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs an API key.",
            new SproutwatchActions(noKey).startListener());

        FakeHost noHandle = new FakeHost();
        noHandle.cfg.setTwitchChannel("streamer");
        noHandle.cfg.setYouTubeEnabled(true);
        noHandle.cfg.setYouTubeApiKey(KEY);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs your @handle or a stream link.",
            new SproutwatchActions(noHandle).startListener());

        FakeHost neither = new FakeHost();
        neither.cfg.setTwitchChannel("streamer");
        neither.cfg.setYouTubeEnabled(true);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs an API key and your @handle or a stream link.",
            new SproutwatchActions(neither).startListener());
    }

    @Test void nothingToStartNamesWhatIsMissing() {
        FakeHost h = new FakeHost();
        assertEquals("No Twitch channel set. Use /sproutwatch channel <name> first.", h.cfg.nothingToStartReason());
        h.cfg.setYouTubeEnabled(true);
        assertEquals("Set a Twitch channel or finish YouTube setup (needs an API key and your @handle or a stream link) first.",
            h.cfg.nothingToStartReason());
        h.cfg.setTwitchEnabled(false);
        h.cfg.setYouTubeHandle("@Streamer");
        assertEquals("Twitch is off and YouTube is on but needs an API key.", h.cfg.nothingToStartReason());
        h.cfg.setYouTubeEnabled(false);
        assertEquals("Twitch is off and YouTube is off. Turn Twitch on, or turn YouTube on and give it an API key.",
            h.cfg.nothingToStartReason());
        h.cfg.setTwitchEnabled(true);
        h.cfg.setTwitchChannel("streamer");
        assertNull(h.cfg.nothingToStartReason());
        h.cfg.setTwitchChannel("");
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeApiKey(KEY);
        assertNull(h.cfg.nothingToStartReason(), "YouTube alone is enough");
    }

    // ---- actions -----------------------------------------------------------------------------

    @Test void toggleTwitchSavesAndRestartsARunningListener() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Twitch chat is off.", a.setTwitchEnabled(false));
        assertFalse(h.cfg.isTwitchEnabled());
        assertEquals(0, h.startCalls);
        h.running = true;
        assertEquals("Twitch chat is on; listener restarted.", a.setTwitchEnabled(true));
        assertTrue(h.cfg.isTwitchEnabled());
        assertEquals(1, h.startCalls);
        h.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals("Twitch chat is off. Nothing else is set up, so the listener stopped.", a.setTwitchEnabled(false));
        assertFalse(h.running);
        assertEquals(3, h.saveCalls);
    }

    @Test void toggleYouTubeSavesNotesWhatIsMissingAndRestarts() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.", a.setYouTubeEnabled(true));
        assertTrue(h.cfg.isYouTubeEnabled());
        h.cfg.setYouTubeHandle("@Streamer");
        h.cfg.setYouTubeApiKey(KEY);
        h.running = true;
        assertEquals("YouTube chat is on; listener restarted.", a.setYouTubeEnabled(true));
        assertEquals("YouTube chat is off; listener restarted.", a.setYouTubeEnabled(false));
        assertFalse(h.cfg.isYouTubeEnabled());
        assertEquals(2, h.startCalls);
        assertEquals(3, h.saveCalls);
    }

    @Test void setHandleParsesAndRejects() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube channel set to @Streamer.", a.setYouTubeHandle("https://www.youtube.com/@Streamer/live"));
        assertEquals("@Streamer", h.cfg.getYouTubeHandle());
        assertEquals(1, h.saveCalls);
        for (String bad : new String[]{null, "", "  ", "https://example.com/@x", "two words"}) {
            assertEquals("Invalid YouTube handle. Use @name or a youtube.com/@name link.", a.setYouTubeHandle(bad));
        }
        assertEquals(1, h.saveCalls);
        assertEquals("@Streamer", h.cfg.getYouTubeHandle());
    }

    @Test void setHandleRestartsOnlyWhenYouTubeIsOnAndRunning() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.running = true;
        assertEquals("YouTube channel set to @alpha.", a.setYouTubeHandle("@alpha"));
        assertEquals(0, h.startCalls, "YouTube off: the running listener is unaffected");
        h.cfg.setYouTubeEnabled(true);
        assertEquals("YouTube channel set to @bravo.", a.setYouTubeHandle("@bravo"));
        assertEquals(0, h.startCalls, "YouTube still lacks a key: nothing to restart for");
        h.cfg.setYouTubeApiKey(KEY);
        assertEquals("YouTube channel set to @charlie; listener restarted.", a.setYouTubeHandle("@charlie"));
        assertEquals(1, h.startCalls);
    }

    // ---- no needless restarts ----------------------------------------------------------------

    @Test void enablingAnIncompleteYouTubeDoesNotRestartARunningTwitch() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        h.running = true;
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.", a.setYouTubeEnabled(true));
        assertEquals(0, h.startCalls);
        assertTrue(h.running);
        assertEquals("YouTube channel set to @Streamer.", a.setYouTubeHandle("@Streamer"));
        assertEquals(0, h.startCalls, "a handle alone does not complete YouTube");
        assertEquals("YouTube chat is off.", a.setYouTubeEnabled(false));
        assertEquals(0, h.startCalls, "YouTube was never configured, so turning it off changes nothing");
    }

    @Test void completingYouTubeWhileRunningRestarts() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeHandle("@Streamer");
        h.running = true;
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube API key saved (AIza...abcd); listener restarted.", a.setYouTubeKey(KEY));
        assertEquals(1, h.startCalls);
    }

    @Test void channelChangeWhileTwitchIsOffDoesNotRestart() {
        FakeHost h = youTubeReady();
        h.cfg.setTwitchEnabled(false);
        h.running = true;
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Channel set to #streamer (Twitch chat is off).", a.setChannel("streamer"));
        assertEquals(0, h.startCalls);
        assertEquals("streamer", h.cfg.getTwitchChannel());
        h.running = false;
        assertEquals("Channel set to #other (Twitch chat is off).", a.setChannel("other"));
        assertEquals(2, h.saveCalls);
    }

    @Test void setKeyValidatesAndOnlyEverShowsTheMaskedKey() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        List<String> replies = new ArrayList<>();
        replies.add(a.setYouTubeKey(null));
        replies.add(a.setYouTubeKey("   "));
        replies.add(a.setYouTubeKey("AIzaTEST KEY0123456789abcd"));
        replies.add(a.setYouTubeKey("AIzaShort"));
        assertEquals(List.of(
            "Paste your YouTube API key (from Google Cloud Console).",
            "Paste your YouTube API key (from Google Cloud Console).",
            "That is not an API key: it contains spaces.",
            "That is not an API key: it is too short."), replies);
        assertEquals(0, h.saveCalls);
        assertEquals("", h.cfg.getYouTubeApiKey());

        String ok = a.setYouTubeKey("  " + KEY + "\n");
        assertEquals("YouTube API key saved (AIza...abcd).", ok);
        assertEquals(KEY, h.cfg.getYouTubeApiKey());
        assertEquals(1, h.saveCalls);

        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeHandle("@Streamer");
        h.running = true;
        replies.add(ok);
        replies.add(a.setYouTubeKey(KEY));
        replies.add(a.setYouTubeEnabled(true));
        replies.add(a.startListener());
        replies.add(a.statusReport());
        h.startError = "boom";
        replies.add(a.setYouTubeKey(KEY));
        for (String r : replies) assertFalse(r.contains(KEY), r);
        for (String r : replies) assertFalse(r.contains("0123456789"), r);
    }

    @Test void setVideoParsesClearsAndRejects() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube stream set to video dQw4w9WgXcQ.", a.setYouTubeVideo("https://youtu.be/dQw4w9WgXcQ?t=5"));
        assertEquals("dQw4w9WgXcQ", h.cfg.getYouTubeVideo());
        assertEquals("Invalid YouTube stream link. Paste a watch link or the 11-character video ID.", a.setYouTubeVideo("nope"));
        assertEquals("dQw4w9WgXcQ", h.cfg.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.", a.setYouTubeVideo("  "));
        assertEquals("", h.cfg.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.", a.setYouTubeVideo(null));
        assertEquals(3, h.saveCalls);
    }

    @Test void setStreamHoursClampsAndRestarts() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("YouTube stream length is now 8h (chat reads are paced so the daily quota lasts that long).", a.setYouTubeStreamHours(8));
        assertEquals("YouTube stream length is now 2.5h (chat reads are paced so the daily quota lasts that long).", a.setYouTubeStreamHours(2.5));
        assertEquals("YouTube stream length is now 24h (chat reads are paced so the daily quota lasts that long).", a.setYouTubeStreamHours(100));
        assertEquals("YouTube stream length is now 1h (chat reads are paced so the daily quota lasts that long).", a.setYouTubeStreamHours(0));
        assertEquals(4, h.saveCalls);
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeApiKey(KEY);
        h.cfg.setYouTubeHandle("@Streamer");
        h.running = true;
        assertEquals("YouTube stream length is now 6h (chat reads are paced so the daily quota lasts that long); listener restarted.", a.setYouTubeStreamHours(6));
        assertEquals(1, h.startCalls);
    }

    @Test void removeClearsEachSavedFieldAndRestartsOnlyWhenItMattered() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.cfg.setTwitchChannel("streamer");
        h.cfg.setYouTubeHandle("@Streamer");
        h.cfg.setYouTubeApiKey(KEY);
        assertEquals("Twitch channel removed.", a.removeChannel());
        assertEquals("", h.cfg.getTwitchChannel());
        assertEquals("YouTube channel removed.", a.removeYouTubeHandle());
        assertEquals("", h.cfg.getYouTubeHandle());
        assertEquals("YouTube API key removed.", a.removeYouTubeKey());
        assertEquals("", h.cfg.getYouTubeApiKey());
        assertEquals(3, h.saveCalls);
        assertEquals(0, h.startCalls, "nothing was running");

        h.cfg.setTwitchChannel("streamer");
        h.cfg.setYouTubeEnabled(true);
        h.cfg.setYouTubeHandle("@Streamer");
        h.cfg.setYouTubeApiKey(KEY);
        h.running = true;
        String reply = a.removeYouTubeKey();
        assertTrue(reply.startsWith("YouTube API key removed"), reply);
        assertFalse(reply.contains(KEY));
        assertEquals(1, h.startCalls, "a running listener restarts without YouTube");
    }

    // ---- status ------------------------------------------------------------------------------

    @Test void maskedKey() {
        assertEquals("not set", StatusSnapshot.maskedKey(null));
        assertEquals("not set", StatusSnapshot.maskedKey("  "));
        assertEquals("set", StatusSnapshot.maskedKey("AIzaShort"));
        assertEquals("set", StatusSnapshot.maskedKey("AIza1234567"));
        assertEquals("AIza...5678", StatusSnapshot.maskedKey("AIza12345678"));
        assertEquals("AIza...abcd", StatusSnapshot.maskedKey(KEY));
    }

    @Test void sourceStatesListConfiguredOrRunningSourcesInOrder() {
        assertEquals(Map.of(), StatusSnapshot.sourceStates(null, false, null, false));
        assertEquals(List.of("Twitch", "YouTube"),
            new ArrayList<>(StatusSnapshot.sourceStates(null, true, null, true).keySet()));
        assertEquals(states("Twitch", "stopped", "YouTube", "stopped"), StatusSnapshot.sourceStates(null, true, null, true));
        assertEquals(states("YouTube", "chat ended"), StatusSnapshot.sourceStates(null, false, "chat ended", false),
            "a running source shows even when its config changed since");
        assertEquals(states("Twitch", "connected to #streamer"), StatusSnapshot.sourceStates("connected to #streamer", true, null, false));
    }

    @Test void joinStates() {
        assertEquals("stopped", StatusSnapshot.joinStates(Map.of()));
        assertEquals("stopped", StatusSnapshot.joinStates(states("Twitch", "stopped", "YouTube", "stopped")));
        assertEquals("connected to #streamer", StatusSnapshot.joinStates(states("Twitch", "connected to #streamer")));
        assertEquals("connected to YouTube (@Streamer)", StatusSnapshot.joinStates(states("YouTube", "connected to YouTube (@Streamer)")));
        assertEquals("Twitch: connected to #streamer · YouTube: reconnecting",
            StatusSnapshot.joinStates(states("Twitch", "connected to #streamer", "YouTube", "reconnecting")));
    }

    @Test void listenerLineToneAndButtonTwitchOnly() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        h.running = true;
        h.states = states("Twitch", "connected to #streamer");
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("Listener: connected to #streamer", s.listenerLine());
        assertEquals("ValueGood", s.listenerTone());
        assertEquals("stop", s.runButton());
        h.states = states("Twitch", "reconnecting");
        s = new SproutwatchActions(h).snapshot();
        assertEquals("ValueBad", s.listenerTone());
        assertEquals("connecting", s.runButton());
    }

    @Test void listenerLineToneAndButtonYouTubeOnly() {
        FakeHost h = youTubeReady();
        h.running = true;
        h.states = states("YouTube", "connecting (finding stream)");
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("Listener: connecting (finding stream)", s.listenerLine());
        assertEquals("ValueWarn", s.listenerTone());
        assertEquals("connecting", s.runButton());
        h.states = states("YouTube", "connected to YouTube (@Streamer)");
        s = new SproutwatchActions(h).snapshot();
        assertEquals("ValueGood", s.listenerTone());
        assertEquals("stop", s.runButton());
        h.states = states("YouTube", "quota exhausted (resets 00:00)");
        assertEquals("stop", new SproutwatchActions(h).snapshot().runButton(), "waiting for the reset is settled, not connecting");
        h.running = false;
        h.states = states("YouTube", "not live");
        assertEquals("start", new SproutwatchActions(h).snapshot().runButton(), "an ended source runs no more");
    }

    @Test void listenerLineToneAndButtonBothSources() {
        FakeHost h = youTubeReady();
        h.cfg.setTwitchChannel("streamer");
        h.running = true;
        h.states = states("Twitch", "connected to #streamer", "YouTube", "connected to YouTube (@Streamer)");
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("Listener: Twitch: connected to #streamer · YouTube: connected to YouTube (@Streamer)", s.listenerLine());
        assertEquals("Twitch: connected to #streamer · YouTube: connected to YouTube (@Streamer)", s.detailValues().get(1));
        assertEquals("ValueGood", s.listenerTone());
        assertEquals("stop", s.runButton());

        h.states = states("Twitch", "connected to #streamer", "YouTube", "connecting (finding stream)");
        s = new SproutwatchActions(h).snapshot();
        assertEquals("ValueWarn", s.listenerTone(), "worst tone wins");
        assertEquals("connecting", s.runButton(), "one source still connecting");

        h.states = states("Twitch", "reconnecting", "YouTube", "connecting (finding stream)");
        assertEquals("ValueBad", new SproutwatchActions(h).snapshot().listenerTone());

        h.states = states("Twitch", "connected to #streamer", "YouTube", "chat ended");
        s = new SproutwatchActions(h).snapshot();
        assertEquals("ValuePlain", s.listenerTone());
        assertEquals("stop", s.runButton(), "an ended source is not running; the rest are connected");

        h.running = false;
        h.states = states("Twitch", "stopped", "YouTube", "stopped");
        s = new SproutwatchActions(h).snapshot();
        assertEquals("Listener: stopped", s.listenerLine());
        assertEquals("ValuePlain", s.listenerTone());
        assertEquals("start", s.runButton());
    }

    @Test void statusReportShowsYouTubeLinesWithAMaskedKey() {
        FakeHost h = youTubeReady();
        h.cfg.setTwitchChannel("streamer");
        h.cfg.setYouTubeQuota("2026-10-03", 120);
        h.running = true;
        h.states = states("Twitch", "connected to #streamer", "YouTube", "connected to YouTube (@Streamer)");
        String r = new SproutwatchActions(h).statusReport();
        assertTrue(r.startsWith(String.join("\n",
            "Listener: Twitch: connected to #streamer · YouTube: connected to YouTube (@Streamer)",
            "Channel: #streamer",
            "Twitch JOIN/PART feed: OFF (Twitch did not grant membership; only viewers who chat will appear)",
            "YouTube: connected to YouTube (@Streamer)",
            "YouTube channel: @Streamer",
            "YouTube key: AIza...abcd",
            "YouTube quota: 120 / 9,800 used today (resets 00:00)",
            "Seen in chat: 0")), r);
        assertFalse(r.contains(KEY));

        h.cfg.setYouTubeVideo("dQw4w9WgXcQ");
        h.cfg.setYouTubeQuota("2026-10-02", 120);
        h.running = false;
        h.states = null;
        r = new SproutwatchActions(h).statusReport();
        assertTrue(r.contains("YouTube: stopped\nYouTube channel: video dQw4w9WgXcQ\n"), r);
        assertTrue(r.contains("YouTube quota: 0 / 9,800 used today (resets 00:00)\n"), "yesterday's usage does not count");

        h.cfg.setYouTubeApiKey("");
        r = new SproutwatchActions(h).statusReport();
        assertTrue(r.contains("YouTube: needs an API key\n"), r);
        assertTrue(r.contains("YouTube key: not set\n"), r);

        h.cfg.setYouTubeEnabled(false);
        assertFalse(new SproutwatchActions(h).statusReport().contains("YouTube"), "no YouTube lines while it is off");
    }

    @Test void youTubeStatusForThePage() {
        FakeHost h = youTubeReady();
        h.cfg.setYouTubeQuota("2026-10-03", 77);
        YouTubeStatus y = h.youTubeStatus();
        assertTrue(y.enabled());
        assertTrue(y.configured());
        assertEquals("@Streamer", y.target());
        assertEquals("AIza...abcd", y.maskedKey());
        assertNull(y.missing());
        assertEquals(77, y.quotaUsed());
        assertEquals(9800, y.quotaBudget());
        assertEquals("00:00", y.resetsAt());
        assertFalse(y.toString().contains(KEY));
        assertEquals(y, new SproutwatchActions(h).snapshot().youTube());
    }
}
