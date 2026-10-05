package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** YouTube settings actions, start messages, and the multi-source listener status (Task 8). */
class ChatSourceActionsYouTubeTest {

    static final String KEY = "AIzaTESTKEY0123456789abcd";

    private static Map<String, String> states(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static FakeHost youTubeReady() {
        FakeHost host = new FakeHost();
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeHandle("@Streamer");
        host.config.setYouTubeApiKey(KEY);
        return host;
    }

    // ---- start messages ----------------------------------------------------------------------

    @Test void startMessageForEachSourceCombination() {
        FakeHost both = youTubeReady();
        both.config.setTwitchChannel("streamer");
        assertEquals("Sproutwatch watching Twitch #streamer and YouTube @Streamer; one sprout every 60s.",
            new ChatSourceActions(both).startListener());

        FakeHost youTubeOnly = youTubeReady();
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.",
            new ChatSourceActions(youTubeOnly).startListener());

        FakeHost youTubeVideo = youTubeReady();
        youTubeVideo.config.setYouTubeVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("Sproutwatch watching YouTube video dQw4w9WgXcQ; one sprout every 60s.",
            new ChatSourceActions(youTubeVideo).startListener());

        FakeHost twitchOff = youTubeReady();
        twitchOff.config.setTwitchChannel("streamer");
        twitchOff.config.setTwitchEnabled(false);
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.",
            new ChatSourceActions(twitchOff).startListener());

        FakeHost nothing = new FakeHost();
        nothing.config.setTwitchEnabled(false);
        assertEquals("Sproutwatch started, but no chat source started (see the server log).",
            new ChatSourceActions(nothing).startListener());
    }

    @Test void startMessageListsOnlyWhatActuallyStarted() {
        FakeHost host = youTubeReady();
        host.config.setTwitchChannel("streamer");
        host.youTubeFails = true;
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube could not start (see the server log).",
            new ChatSourceActions(host).startListener());
    }

    @Test void disablingTheOnlyRunningSourceStopsTheListener() {
        FakeHost tw = new FakeHost();
        tw.refuseLikePlugin = true;
        tw.config.setTwitchChannel("streamer");
        SproutwatchActions actions = new SproutwatchActions(tw);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", actions.chatSources().startListener());
        assertTrue(tw.running);
        assertEquals("Twitch chat is off. Nothing else is set up, so the listener stopped.", actions.chatSources().setTwitchEnabled(false));
        assertFalse(tw.running, "Twitch must not keep listening");
        assertEquals("Listener: stopped", actions.snapshot().listenerLine());

        FakeHost youTubeHost = youTubeReady();
        youTubeHost.refuseLikePlugin = true;
        ChatSourceActions chatSources = new ChatSourceActions(youTubeHost);
        assertEquals("Sproutwatch watching YouTube @Streamer; one sprout every 60s.", chatSources.startListener());
        assertEquals("YouTube chat is off. Nothing else is set up, so the listener stopped.", chatSources.setYouTubeEnabled(false));
        assertFalse(youTubeHost.running, "YouTube must not keep listening");
    }

    @Test void aRefusedRestartForAnotherReasonSaysTheListenerStopped() {
        FakeHost host = youTubeReady();
        host.running = true;
        host.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals("YouTube channel set to @bravo. No pen placed yet. Stand where you want it and run /sproutwatch place. The listener stopped.",
            new ChatSourceActions(host).setYouTubeHandle("@bravo"));
        assertFalse(host.running);
    }

    @Test void startMessageNotesAnIncompleteYouTube() {
        FakeHost noKey = new FakeHost();
        noKey.config.setTwitchChannel("streamer");
        noKey.config.setYouTubeEnabled(true);
        noKey.config.setYouTubeHandle("@Streamer");
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs an API key.",
            new ChatSourceActions(noKey).startListener());

        FakeHost noHandle = new FakeHost();
        noHandle.config.setTwitchChannel("streamer");
        noHandle.config.setYouTubeEnabled(true);
        noHandle.config.setYouTubeApiKey(KEY);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs your @handle or a stream link.",
            new ChatSourceActions(noHandle).startListener());

        FakeHost neither = new FakeHost();
        neither.config.setTwitchChannel("streamer");
        neither.config.setYouTubeEnabled(true);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s. YouTube is on but needs an API key and your @handle or a stream link.",
            new ChatSourceActions(neither).startListener());
    }

    @Test void nothingToStartNamesWhatIsMissing() {
        FakeHost host = new FakeHost();
        assertEquals("No Twitch channel set. Use /sproutwatch channel <name> first.", host.config.nothingToStartReason());
        host.config.setYouTubeEnabled(true);
        assertEquals("Set a Twitch channel or finish YouTube setup (needs an API key and your @handle or a stream link) first.",
            host.config.nothingToStartReason());
        host.config.setTwitchEnabled(false);
        host.config.setYouTubeHandle("@Streamer");
        assertEquals("Twitch is off and YouTube is on but needs an API key.", host.config.nothingToStartReason());
        host.config.setYouTubeEnabled(false);
        assertEquals("Twitch is off and YouTube is off. Turn Twitch on, or turn YouTube on and give it an API key.",
            host.config.nothingToStartReason());
        host.config.setTwitchEnabled(true);
        host.config.setTwitchChannel("streamer");
        assertNull(host.config.nothingToStartReason());
        host.config.setTwitchChannel("");
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeApiKey(KEY);
        assertNull(host.config.nothingToStartReason(), "YouTube alone is enough");
    }

    // ---- actions -----------------------------------------------------------------------------

    @Test void toggleTwitchSavesAndRestartsARunningListener() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Twitch chat is off.", chatSources.setTwitchEnabled(false));
        assertFalse(host.config.isTwitchEnabled());
        assertEquals(0, host.startCalls);
        host.running = true;
        assertEquals("Twitch chat is on; listener restarted.", chatSources.setTwitchEnabled(true));
        assertTrue(host.config.isTwitchEnabled());
        assertEquals(1, host.startCalls);
        host.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals("Twitch chat is off. Nothing else is set up, so the listener stopped.", chatSources.setTwitchEnabled(false));
        assertFalse(host.running);
        assertEquals(3, host.saveCalls);
    }

    @Test void toggleYouTubeSavesNotesWhatIsMissingAndRestarts() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.", chatSources.setYouTubeEnabled(true));
        assertTrue(host.config.isYouTubeEnabled());
        host.config.setYouTubeHandle("@Streamer");
        host.config.setYouTubeApiKey(KEY);
        host.running = true;
        assertEquals("YouTube chat is on; listener restarted.", chatSources.setYouTubeEnabled(true));
        assertEquals("YouTube chat is off; listener restarted.", chatSources.setYouTubeEnabled(false));
        assertFalse(host.config.isYouTubeEnabled());
        assertEquals(2, host.startCalls);
        assertEquals(3, host.saveCalls);
    }

    @Test void setHandleParsesAndRejects() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube channel set to @Streamer.", chatSources.setYouTubeHandle("https://www.youtube.com/@Streamer/live"));
        assertEquals("@Streamer", host.config.getYouTubeHandle());
        assertEquals(1, host.saveCalls);
        for (String bad : new String[]{null, "", "  ", "https://example.com/@x", "two words"}) {
            assertEquals("Invalid YouTube handle. Use @name or a youtube.com/@name link.", chatSources.setYouTubeHandle(bad));
        }
        assertEquals(1, host.saveCalls);
        assertEquals("@Streamer", host.config.getYouTubeHandle());
    }

    @Test void setHandleRestartsOnlyWhenYouTubeIsOnAndRunning() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        host.running = true;
        assertEquals("YouTube channel set to @alpha.", chatSources.setYouTubeHandle("@alpha"));
        assertEquals(0, host.startCalls, "YouTube off: the running listener is unaffected");
        host.config.setYouTubeEnabled(true);
        assertEquals("YouTube channel set to @bravo.", chatSources.setYouTubeHandle("@bravo"));
        assertEquals(0, host.startCalls, "YouTube still lacks a key: nothing to restart for");
        host.config.setYouTubeApiKey(KEY);
        assertEquals("YouTube channel set to @charlie; listener restarted.", chatSources.setYouTubeHandle("@charlie"));
        assertEquals(1, host.startCalls);
    }

    // ---- no needless restarts ----------------------------------------------------------------

    @Test void enablingAnIncompleteYouTubeDoesNotRestartARunningTwitch() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        host.running = true;
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube chat is on. It still needs an API key and your @handle or a stream link.", chatSources.setYouTubeEnabled(true));
        assertEquals(0, host.startCalls);
        assertTrue(host.running);
        assertEquals("YouTube channel set to @Streamer.", chatSources.setYouTubeHandle("@Streamer"));
        assertEquals(0, host.startCalls, "a handle alone does not complete YouTube");
        assertEquals("YouTube chat is off.", chatSources.setYouTubeEnabled(false));
        assertEquals(0, host.startCalls, "YouTube was never configured, so turning it off changes nothing");
    }

    @Test void completingYouTubeWhileRunningRestarts() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeHandle("@Streamer");
        host.running = true;
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube API key saved (AIza...abcd); listener restarted.", chatSources.setYouTubeKey(KEY));
        assertEquals(1, host.startCalls);
    }

    @Test void channelChangeWhileTwitchIsOffDoesNotRestart() {
        FakeHost host = youTubeReady();
        host.config.setTwitchEnabled(false);
        host.running = true;
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Channel set to #streamer (Twitch chat is off).", chatSources.setChannel("streamer"));
        assertEquals(0, host.startCalls);
        assertEquals("streamer", host.config.getTwitchChannel());
        host.running = false;
        assertEquals("Channel set to #other (Twitch chat is off).", chatSources.setChannel("other"));
        assertEquals(2, host.saveCalls);
    }

    @Test void setKeyValidatesAndOnlyEverShowsTheMaskedKey() {
        FakeHost host = new FakeHost();
        SproutwatchActions actions = new SproutwatchActions(host);
        List<String> replies = new ArrayList<>();
        replies.add(actions.chatSources().setYouTubeKey(null));
        replies.add(actions.chatSources().setYouTubeKey("   "));
        replies.add(actions.chatSources().setYouTubeKey("AIzaTEST KEY0123456789abcd"));
        replies.add(actions.chatSources().setYouTubeKey("AIzaShort"));
        assertEquals(List.of(
            "Paste your YouTube API key (from Google Cloud Console).",
            "Paste your YouTube API key (from Google Cloud Console).",
            "That is not an API key: it contains spaces.",
            "That is not an API key: it is too short."), replies);
        assertEquals(0, host.saveCalls);
        assertEquals("", host.config.getYouTubeApiKey());

        String ok = actions.chatSources().setYouTubeKey("  " + KEY + "\n");
        assertEquals("YouTube API key saved (AIza...abcd).", ok);
        assertEquals(KEY, host.config.getYouTubeApiKey());
        assertEquals(1, host.saveCalls);

        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeHandle("@Streamer");
        host.running = true;
        replies.add(ok);
        replies.add(actions.chatSources().setYouTubeKey(KEY));
        replies.add(actions.chatSources().setYouTubeEnabled(true));
        replies.add(actions.chatSources().startListener());
        replies.add(actions.statusReport());
        host.startError = "boom";
        replies.add(actions.chatSources().setYouTubeKey(KEY));
        for (String r : replies) assertFalse(r.contains(KEY), r);
        for (String r : replies) assertFalse(r.contains("0123456789"), r);
    }

    @Test void setVideoParsesClearsAndRejects() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube stream set to video dQw4w9WgXcQ.", chatSources.setYouTubeVideo("https://youtu.be/dQw4w9WgXcQ?t=5"));
        assertEquals("dQw4w9WgXcQ", host.config.getYouTubeVideo());
        assertEquals("Invalid YouTube stream link. Paste a watch link or the 11-character video ID.", chatSources.setYouTubeVideo("nope"));
        assertEquals("dQw4w9WgXcQ", host.config.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.", chatSources.setYouTubeVideo("  "));
        assertEquals("", host.config.getYouTubeVideo());
        assertEquals("YouTube stream link cleared; the live stream is found from your @handle.", chatSources.setYouTubeVideo(null));
        assertEquals(3, host.saveCalls);
    }

    @Test void setStreamHoursClampsAndRestarts() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("YouTube stream length is now 8h (chat reads are paced so the daily quota lasts that long).", chatSources.setYouTubeStreamHours(8));
        assertEquals("YouTube stream length is now 2.5h (chat reads are paced so the daily quota lasts that long).", chatSources.setYouTubeStreamHours(2.5));
        assertEquals("YouTube stream length is now 24h (chat reads are paced so the daily quota lasts that long).", chatSources.setYouTubeStreamHours(100));
        assertEquals("YouTube stream length is now 1h (chat reads are paced so the daily quota lasts that long).", chatSources.setYouTubeStreamHours(0));
        assertEquals(4, host.saveCalls);
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeApiKey(KEY);
        host.config.setYouTubeHandle("@Streamer");
        host.running = true;
        assertEquals("YouTube stream length is now 6h (chat reads are paced so the daily quota lasts that long); listener restarted.", chatSources.setYouTubeStreamHours(6));
        assertEquals(1, host.startCalls);
    }

    @Test void removeClearsEachSavedFieldAndRestartsOnlyWhenItMattered() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        host.config.setTwitchChannel("streamer");
        host.config.setYouTubeHandle("@Streamer");
        host.config.setYouTubeApiKey(KEY);
        assertEquals("Twitch channel removed.", chatSources.removeChannel());
        assertEquals("", host.config.getTwitchChannel());
        assertEquals("YouTube channel removed.", chatSources.removeYouTubeHandle());
        assertEquals("", host.config.getYouTubeHandle());
        assertEquals("YouTube API key removed.", chatSources.removeYouTubeKey());
        assertEquals("", host.config.getYouTubeApiKey());
        assertEquals(3, host.saveCalls);
        assertEquals(0, host.startCalls, "nothing was running");

        host.config.setTwitchChannel("streamer");
        host.config.setYouTubeEnabled(true);
        host.config.setYouTubeHandle("@Streamer");
        host.config.setYouTubeApiKey(KEY);
        host.running = true;
        String reply = chatSources.removeYouTubeKey();
        assertTrue(reply.startsWith("YouTube API key removed"), reply);
        assertFalse(reply.contains(KEY));
        assertEquals(1, host.startCalls, "a running listener restarts without YouTube");
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
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        host.running = true;
        host.states = states("Twitch", "connected to #streamer");
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("Listener: connected to #streamer", s.listenerLine());
        assertEquals("ValueGood", s.listenerTone());
        assertEquals(RunButton.STOP, s.runButton());
        host.states = states("Twitch", "reconnecting");
        s = new SproutwatchActions(host).snapshot();
        assertEquals("ValueBad", s.listenerTone());
        assertEquals(RunButton.CONNECTING, s.runButton());
    }

    @Test void listenerLineToneAndButtonYouTubeOnly() {
        FakeHost host = youTubeReady();
        host.running = true;
        host.states = states("YouTube", "connecting (finding stream)");
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("Listener: connecting (finding stream)", s.listenerLine());
        assertEquals("ValueWarn", s.listenerTone());
        assertEquals(RunButton.CONNECTING, s.runButton());
        host.states = states("YouTube", "connected to YouTube (@Streamer)");
        s = new SproutwatchActions(host).snapshot();
        assertEquals("ValueGood", s.listenerTone());
        assertEquals(RunButton.STOP, s.runButton());
        host.states = states("YouTube", "quota exhausted (resets 00:00)");
        assertEquals(RunButton.STOP, new SproutwatchActions(host).snapshot().runButton(), "waiting for the reset is settled, not connecting");
        host.running = false;
        host.states = states("YouTube", "not live");
        assertEquals(RunButton.START, new SproutwatchActions(host).snapshot().runButton(), "an ended source runs no more");
    }

    @Test void listenerLineToneAndButtonBothSources() {
        FakeHost host = youTubeReady();
        host.config.setTwitchChannel("streamer");
        host.running = true;
        host.states = states("Twitch", "connected to #streamer", "YouTube", "connected to YouTube (@Streamer)");
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("Listener: Twitch: connected to #streamer · YouTube: connected to YouTube (@Streamer)", s.listenerLine());
        assertEquals("Twitch: connected to #streamer · YouTube: connected to YouTube (@Streamer)", s.detailValues().get(1));
        assertEquals("ValueGood", s.listenerTone());
        assertEquals(RunButton.STOP, s.runButton());

        host.states = states("Twitch", "connected to #streamer", "YouTube", "connecting (finding stream)");
        s = new SproutwatchActions(host).snapshot();
        assertEquals("ValueWarn", s.listenerTone(), "worst tone wins");
        assertEquals(RunButton.CONNECTING, s.runButton(), "one source still connecting");

        host.states = states("Twitch", "reconnecting", "YouTube", "connecting (finding stream)");
        assertEquals("ValueBad", new SproutwatchActions(host).snapshot().listenerTone());

        host.states = states("Twitch", "connected to #streamer", "YouTube", "chat ended");
        s = new SproutwatchActions(host).snapshot();
        assertEquals("ValuePlain", s.listenerTone());
        assertEquals(RunButton.STOP, s.runButton(), "an ended source is not running; the rest are connected");

        host.running = false;
        host.states = states("Twitch", "stopped", "YouTube", "stopped");
        s = new SproutwatchActions(host).snapshot();
        assertEquals("Listener: stopped", s.listenerLine());
        assertEquals("ValuePlain", s.listenerTone());
        assertEquals(RunButton.START, s.runButton());
    }

    @Test void statusReportShowsYouTubeLinesWithAMaskedKey() {
        FakeHost host = youTubeReady();
        host.config.setTwitchChannel("streamer");
        host.config.setYouTubeQuota("2026-10-03", 120);
        host.running = true;
        host.states = states("Twitch", "connected to #streamer", "YouTube", "connected to YouTube (@Streamer)");
        String r = new SproutwatchActions(host).statusReport();
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

        host.config.setYouTubeVideo("dQw4w9WgXcQ");
        host.config.setYouTubeQuota("2026-10-02", 120);
        host.running = false;
        host.states = null;
        r = new SproutwatchActions(host).statusReport();
        assertTrue(r.contains("YouTube: stopped\nYouTube channel: video dQw4w9WgXcQ\n"), r);
        assertTrue(r.contains("YouTube quota: 0 / 9,800 used today (resets 00:00)\n"), "yesterday's usage does not count");

        host.config.setYouTubeApiKey("");
        r = new SproutwatchActions(host).statusReport();
        assertTrue(r.contains("YouTube: needs an API key\n"), r);
        assertTrue(r.contains("YouTube key: not set\n"), r);

        host.config.setYouTubeEnabled(false);
        assertFalse(new SproutwatchActions(host).statusReport().contains("YouTube"), "no YouTube lines while it is off");
    }

    @Test void youTubeStatusForThePage() {
        FakeHost host = youTubeReady();
        host.config.setYouTubeQuota("2026-10-03", 77);
        YouTubeStatus y = host.youTubeStatus();
        assertTrue(y.enabled());
        assertTrue(y.configured());
        assertEquals("@Streamer", y.target());
        assertEquals("AIza...abcd", y.maskedKey());
        assertNull(y.missing());
        assertEquals(77, y.quotaUsed());
        assertEquals(9800, y.quotaBudget());
        assertEquals("00:00", y.resetsAt());
        assertFalse(y.toString().contains(KEY));
        assertEquals(y, new SproutwatchActions(host).snapshot().youTube());
    }
}
