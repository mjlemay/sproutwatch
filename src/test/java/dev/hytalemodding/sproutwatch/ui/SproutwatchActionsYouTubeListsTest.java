package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest.FakeHost;
import dev.hytalemodding.sproutwatch.youtube.YtException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Allow/ignore lists with YouTube entries (Task 10): input forms, immediate adds, async handle lookups. */
class SproutwatchActionsYouTubeListsTest {

    static final String KEY = "AIzaTESTKEY0123456789abcd";
    static final String CH = "UCabcdefghijklmnopqrstuV";
    static final String YT = "yt:" + CH;
    static final String NO_KEY = "Add your YouTube API key first, or paste the channel link (youtube.com/channel/UC...).";

    private static FakeHost withKey() {
        FakeHost h = new FakeHost();
        h.cfg.setYouTubeApiKey(KEY);
        return h;
    }

    // ---- parsing table -----------------------------------------------------------------------

    @Test void everyInputFormIsClassified() {
        Map<String, ViewerEntry> table = new java.util.LinkedHashMap<>();
        table.put("alice", new ViewerEntry.Twitch("alice"));
        table.put("@Alice", new ViewerEntry.Twitch("alice"));
        table.put("  Bob_99 ", new ViewerEntry.Twitch("bob_99"));
        table.put("yt:@Streamer", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("YT:@Streamer", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("yt:Streamer", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("yt:" + CH, new ViewerEntry.YouTubeChannel(YT));
        table.put(CH, new ViewerEntry.YouTubeChannel(YT));
        table.put("youtube.com/@Streamer", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("https://www.youtube.com/@Streamer", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("m.youtube.com/@Streamer/streams", new ViewerEntry.YouTubeHandle("@Streamer"));
        table.put("youtube.com/channel/" + CH, new ViewerEntry.YouTubeChannel(YT));
        table.put("https://www.youtube.com/channel/" + CH + "/videos", new ViewerEntry.YouTubeChannel(YT));
        table.put("HTTPS://YouTube.com/channel/" + CH, new ViewerEntry.YouTubeChannel(YT));
        table.put("yt:youtube.com/channel/" + CH, new ViewerEntry.YouTubeChannel(YT));
        for (Map.Entry<String, ViewerEntry> e : table.entrySet()) {
            assertEquals(e.getValue(), ViewerEntry.parse(e.getKey()), e.getKey());
        }
        for (String bad : new String[]{"https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtu.be/dQw4w9WgXcQ",
                "yt:", "yt:!!", "yt:" + CH.toLowerCase(java.util.Locale.ROOT) + "!", "youtube.com/c/Name"}) {
            assertInstanceOf(ViewerEntry.Invalid.class, ViewerEntry.parse(bad), bad);
            assertEquals(ViewerEntry.INVALID_YOUTUBE, ((ViewerEntry.Invalid) ViewerEntry.parse(bad)).reply(), bad);
        }
        for (String bad : new String[]{null, "", "   ", "!!!"}) {
            assertEquals(new ViewerEntry.Invalid("Invalid login."), ViewerEntry.parse(bad), String.valueOf(bad));
        }
        // A lowercase "uc..." is a (long) Twitch login, not a channel ID.
        assertInstanceOf(ViewerEntry.Twitch.class, ViewerEntry.parse(CH.toLowerCase(java.util.Locale.ROOT)));
    }

    // ---- immediate channel-id adds -----------------------------------------------------------

    @Test void channelIdAddsAtOnceWithoutNetwork() {
        FakeHost h = new FakeHost();   // no key needed for an ID
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Added " + CH + " (YouTube) to the allow list.", a.addAllow("youtube.com/channel/" + CH));
        assertEquals(CH + " (YouTube) is already on the allow list.", a.addAllow("yt:" + CH));
        assertEquals(Set.of(YT), h.cfg.allowedLogins());
        assertEquals(1, h.saveCalls);
        assertTrue(h.lookups.isEmpty());
        h.cfg.setYouTubeLabel(YT, "@Streamer");
        assertEquals("@Streamer (YouTube) is already on the allow list.", a.addAllow(CH), "a known label is shown");
        assertEquals("Removed @Streamer (YouTube) from the allow list.", a.removeAllow(CH));
        assertEquals(CH + " (YouTube) is not on the allow list.", a.removeAllow(CH));
        assertTrue(h.cfg.youTubeLabel(YT).isEmpty(), "label dropped with the last entry");
    }

    @Test void channelIdIgnoreDropsTheViewerFromTheRoster() {
        FakeHost h = new FakeHost();
        h.roster.apply(new RosterEvent.Chat(YT, "hi"), 1L);
        assertEquals(1, h.roster.size());
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Added " + CH + " (YouTube) to the ignore list.", a.addIgnore(CH));
        assertEquals(0, h.roster.size());
        h.roster.apply(new RosterEvent.Chat(YT, "again"), 2L);
        assertEquals(0, h.roster.size(), "stays out");
        assertTrue(h.cfg.ignoredLogins().contains(YT));
    }

    // ---- handle lookups ----------------------------------------------------------------------

    @Test void handleWithoutKeyRepliesWithoutLookup() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals(NO_KEY, a.addAllow("yt:@Streamer"));
        assertEquals(NO_KEY, a.addIgnore("youtube.com/@Streamer"));
        assertEquals(NO_KEY, a.removeAllow("yt:@Streamer"));
        assertEquals(NO_KEY, a.removeIgnore("https://youtube.com/@Streamer"));
        assertTrue(h.lookups.isEmpty());
        assertEquals(0, h.saveCalls);
        assertEquals(Optional.empty(), a.lastLookupMessage());
    }

    @Test void handleAddResolvesAsyncAndReportsBack() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Looking up @Streamer on YouTube...", a.addAllow("youtube.com/@Streamer"));
        assertEquals(List.of("@Streamer"), h.lookups);
        assertEquals(Optional.of("Looking up @Streamer on YouTube..."), a.lastLookupMessage());
        assertEquals(0, h.saveCalls);
        assertTrue(h.cfg.allowedLogins().isEmpty());

        h.lookupFutures.get(0).complete(CH);
        assertEquals(Set.of(YT), h.cfg.allowedLogins());
        assertEquals(Optional.of("@Streamer"), h.cfg.youTubeLabel(YT));
        assertEquals(Optional.of("Added @Streamer (YouTube) to the allow list."), a.lastLookupMessage());
        assertEquals(1, h.saveCalls);
        assertEquals(1, h.changedCalls);

        assertEquals("Looking up @Streamer on YouTube...", a.addAllow("yt:@Streamer"));
        h.lookupFutures.get(1).complete(CH);
        assertEquals(Optional.of("@Streamer (YouTube) is already on the allow list."), a.lastLookupMessage());
        assertEquals(2, h.changedCalls);
    }

    @Test void handleIgnoreResolvesAndPartsTheViewer() {
        FakeHost h = withKey();
        h.roster.apply(new RosterEvent.Chat(YT, "hi"), 1L);
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Looking up @Streamer on YouTube...", a.addIgnore("yt:@Streamer"));
        h.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("Added @Streamer (YouTube) to the ignore list."), a.lastLookupMessage());
        assertTrue(h.cfg.ignoredLogins().contains(YT));
        assertEquals(0, h.roster.size());
    }

    @Test void lookupFailuresReportEachKindAndChangeNothing() {
        Map<Throwable, String> cases = new java.util.LinkedHashMap<>();
        cases.put(new YtException(YtException.Kind.NOT_FOUND, "x"), "No YouTube channel @Streamer found.");
        cases.put(new YtException(YtException.Kind.TRANSIENT, "x"), "YouTube lookup failed; try again.");
        cases.put(new YtException(YtException.Kind.KEY_INVALID, "x"), "YouTube API key rejected.");
        cases.put(new YtException(YtException.Kind.QUOTA_EXCEEDED, "x"),
            "YouTube quota used up for today; paste the channel link instead.");
        cases.put(new YtException(YtException.Kind.REJECTED, "x"), "YouTube lookup failed; try again.");
        cases.put(new ActionsHost.NoYouTubeKey(), NO_KEY);
        cases.put(new ActionsHost.LookupUnavailable(), "YouTube lookup unavailable (server stopping).");
        cases.put(new IllegalStateException("anything else"), "YouTube lookup failed; try again.");
        cases.put(new java.util.concurrent.CompletionException(new YtException(YtException.Kind.NOT_FOUND, "x")),
            "No YouTube channel @Streamer found.");
        cases.put(new RuntimeException("boom"), "YouTube lookup failed; try again.");
        for (Map.Entry<Throwable, String> c : cases.entrySet()) {
            FakeHost h = withKey();
            SproutwatchActions a = new SproutwatchActions(h);
            a.addIgnore("yt:@Streamer");
            h.lookupFutures.get(0).completeExceptionally(c.getKey());
            assertEquals(Optional.of(c.getValue()), a.lastLookupMessage(), c.getKey().toString());
            assertFalse(a.lastLookupMessage().get().contains(KEY));
            assertFalse(h.cfg.ignoredLogins().stream().anyMatch(k -> k.startsWith("yt:")));
            assertTrue(h.cfg.youTubeLabels().isEmpty());
            assertEquals(0, h.saveCalls);
            assertEquals(1, h.changedCalls, "the page refreshes to show the outcome");
        }
    }

    // ---- removal -----------------------------------------------------------------------------

    @Test void removeByStoredLabelNeedsNoNetwork() {
        FakeHost h = new FakeHost();   // no key: the label alone is enough
        h.cfg.addIgnore(YT);
        h.cfg.setYouTubeLabel(YT, "@Streamer");
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Removed @Streamer (YouTube) from the ignore list.", a.removeIgnore("yt:@streamer"));
        assertTrue(h.lookups.isEmpty());
        assertFalse(h.cfg.ignoredLogins().contains(YT));
        assertTrue(h.cfg.youTubeLabel(YT).isEmpty(), "label cleaned up");
        assertEquals(1, h.saveCalls);
    }

    @Test void removeByLabelKeepsTheLabelWhileTheOtherListStillHasIt() {
        FakeHost h = new FakeHost();
        h.cfg.addIgnore(YT);
        h.cfg.addAllow(YT);
        h.cfg.setYouTubeLabel(YT, "@Streamer");
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Removed @Streamer (YouTube) from the allow list.", a.removeAllow("youtube.com/@Streamer"));
        assertEquals(Optional.of("@Streamer"), h.cfg.youTubeLabel(YT));
        assertEquals("@Streamer (YouTube) is not on the allow list.", a.removeAllow("yt:@Streamer"));
    }

    @Test void removeByUnknownHandleLooksItUp() {
        FakeHost h = withKey();
        h.cfg.addAllow(YT);
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Looking up @Streamer on YouTube...", a.removeAllow("yt:@Streamer"));
        assertEquals(List.of("@Streamer"), h.lookups);
        h.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("Removed @Streamer (YouTube) from the allow list."), a.lastLookupMessage());
        assertTrue(h.cfg.allowedLogins().isEmpty());
        assertEquals(1, h.saveCalls);
        assertEquals(1, h.changedCalls);

        a.removeAllow("yt:@Other");
        h.lookupFutures.get(1).complete("UCzzzzzzzzzzzzzzzzzzzzzz");
        assertEquals(Optional.of("@Other (YouTube) is not on the allow list."), a.lastLookupMessage());
        assertEquals(1, h.saveCalls);
    }

    @Test void invalidYouTubeInputIsRejected() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        String watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        assertEquals(ViewerEntry.INVALID_YOUTUBE, a.addAllow(watch));
        assertEquals(ViewerEntry.INVALID_YOUTUBE, a.removeIgnore("yt:!!"));
        assertTrue(h.lookups.isEmpty());
        assertEquals(0, h.saveCalls);
    }

    @Test void twitchFormsAreUnchanged() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Added alice to the allow list.", a.addAllow("@Alice"));
        assertEquals("Added bob to the ignore list.", a.addIgnore("Bob"));
        assertTrue(h.lookups.isEmpty(), "bare @name is Twitch, never a YouTube lookup");
    }

    // ---- review fixes ------------------------------------------------------------------------

    @Test void commandCallerIsToldTheOutcome() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        List<String> told = new java.util.ArrayList<>();
        assertEquals("Looking up @Streamer on YouTube...", a.addIgnore("yt:@Streamer", told::add));
        assertTrue(told.isEmpty(), "nothing until the lookup ends");
        h.lookupFutures.get(0).complete(CH);
        assertEquals(List.of("Added @Streamer (YouTube) to the ignore list."), told);
        assertEquals(Optional.of("Added @Streamer (YouTube) to the ignore list."), a.lastLookupMessage(), "the page still gets it");
        assertEquals(1, h.changedCalls);

        a.removeAllow("yt:@Ghost", told::add);
        h.lookupFutures.get(1).completeExceptionally(new YtException(YtException.Kind.NOT_FOUND, "x"));
        assertEquals("No YouTube channel @Ghost found.", told.get(1));

        assertEquals("Added @Streamer (YouTube) to the allow list.", a.addAllow(CH, told::add), "the known label is shown");
        assertEquals(2, told.size(), "immediate adds answer in the reply only");
    }

    @Test void aThrowingCallbackDoesNotBreakTheLookup() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        a.addAllow("yt:@Streamer", msg -> { throw new IllegalStateException("player left"); });
        h.lookupFutures.get(0).complete(CH);
        assertEquals(Set.of(YT), h.cfg.allowedLogins());
        assertEquals(1, h.changedCalls);
    }

    @Test void successfulLookupRefreshesTheLabelOfAnExistingEntry() {
        FakeHost h = withKey();
        h.cfg.addAllow(YT);
        h.cfg.setYouTubeLabel(YT, "@oldname");
        SproutwatchActions a = new SproutwatchActions(h);
        a.addAllow("yt:@NewName");
        h.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("@NewName"), h.cfg.youTubeLabel(YT));
        assertEquals(Optional.of("@NewName (YouTube) is already on the allow list."), a.lastLookupMessage());
        assertEquals(1, h.saveCalls, "the refreshed label is saved");
    }

    @Test void atMostFiveLookupsAreInFlight() {
        FakeHost h = withKey();
        SproutwatchActions a = new SproutwatchActions(h);
        for (int i = 0; i < SproutwatchActions.MAX_PENDING_LOOKUPS; i++) {
            assertEquals("Looking up @user" + i + " on YouTube...", a.addIgnore("yt:@user" + i));
        }
        assertEquals(SproutwatchActions.TOO_MANY_REPLY, a.addIgnore("yt:@onemore"));
        assertEquals(5, h.lookups.size());
        h.lookupFutures.get(0).completeExceptionally(new YtException(YtException.Kind.TRANSIENT, "x"));
        assertEquals("Looking up @onemore on YouTube...", a.addIgnore("yt:@onemore"), "a finished lookup frees a slot");
        assertEquals(6, h.lookups.size());
    }
}
