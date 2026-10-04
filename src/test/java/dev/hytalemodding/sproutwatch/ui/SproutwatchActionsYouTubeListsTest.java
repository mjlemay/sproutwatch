package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest.FakeHost;
import dev.hytalemodding.sproutwatch.youtube.YouTubeException;
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
        FakeHost host = new FakeHost();
        host.config.setYouTubeApiKey(KEY);
        return host;
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
            assertEquals(new ViewerEntry.Invalid("Invalid user name."), ViewerEntry.parse(bad), String.valueOf(bad));
        }
        // A lowercase "uc..." is a (long) Twitch login, not a channel ID.
        assertInstanceOf(ViewerEntry.Twitch.class, ViewerEntry.parse(CH.toLowerCase(java.util.Locale.ROOT)));
    }

    // ---- immediate channel-id adds -----------------------------------------------------------

    @Test void channelIdAddsAtOnceWithoutNetwork() {
        FakeHost host = new FakeHost();   // no key needed for an ID
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Added " + CH + " (YouTube) to the allow list.", a.addAllow("youtube.com/channel/" + CH));
        assertEquals(CH + " (YouTube) is already on the allow list.", a.addAllow("yt:" + CH));
        assertEquals(Set.of(YT), host.config.allowedViewers());
        assertEquals(1, host.saveCalls);
        assertTrue(host.lookups.isEmpty());
        host.config.setYouTubeLabel(YT, "@Streamer");
        assertEquals("@Streamer (YouTube) is already on the allow list.", a.addAllow(CH), "a known label is shown");
        assertEquals("Removed @Streamer (YouTube) from the allow list.", a.removeAllow(CH));
        assertEquals(CH + " (YouTube) is not on the allow list.", a.removeAllow(CH));
        assertTrue(host.config.youTubeLabel(YT).isEmpty(), "label dropped with the last entry");
    }

    @Test void channelIdIgnoreDropsTheViewerFromTheRoster() {
        FakeHost host = new FakeHost();
        host.roster.apply(new RosterEvent.Chat(YT, "hi"), 1L);
        assertEquals(1, host.roster.size());
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Added " + CH + " (YouTube) to the ignore list.", a.addIgnore(CH));
        assertEquals(0, host.roster.size());
        host.roster.apply(new RosterEvent.Chat(YT, "again"), 2L);
        assertEquals(0, host.roster.size(), "stays out");
        assertTrue(host.config.ignoredViewers().contains(YT));
    }

    // ---- handle lookups ----------------------------------------------------------------------

    @Test void handleWithoutKeyRepliesWithoutLookup() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals(NO_KEY, a.addAllow("yt:@Streamer"));
        assertEquals(NO_KEY, a.addIgnore("youtube.com/@Streamer"));
        assertEquals(NO_KEY, a.removeAllow("yt:@Streamer"));
        assertEquals(NO_KEY, a.removeIgnore("https://youtube.com/@Streamer"));
        assertTrue(host.lookups.isEmpty());
        assertEquals(0, host.saveCalls);
        assertEquals(Optional.empty(), a.lastLookupMessage());
    }

    @Test void handleAddResolvesAsyncAndReportsBack() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Looking up @Streamer on YouTube...", a.addAllow("youtube.com/@Streamer"));
        assertEquals(List.of("@Streamer"), host.lookups);
        assertEquals(Optional.of("Looking up @Streamer on YouTube..."), a.lastLookupMessage());
        assertEquals(0, host.saveCalls);
        assertTrue(host.config.allowedViewers().isEmpty());

        host.lookupFutures.get(0).complete(CH);
        assertEquals(Set.of(YT), host.config.allowedViewers());
        assertEquals(Optional.of("@Streamer"), host.config.youTubeLabel(YT));
        assertEquals(Optional.of("Added @Streamer (YouTube) to the allow list."), a.lastLookupMessage());
        assertEquals(1, host.saveCalls);
        assertEquals(1, host.changedCalls);

        assertEquals("Looking up @Streamer on YouTube...", a.addAllow("yt:@Streamer"));
        host.lookupFutures.get(1).complete(CH);
        assertEquals(Optional.of("@Streamer (YouTube) is already on the allow list."), a.lastLookupMessage());
        assertEquals(2, host.changedCalls);
    }

    @Test void handleIgnoreResolvesAndPartsTheViewer() {
        FakeHost host = withKey();
        host.roster.apply(new RosterEvent.Chat(YT, "hi"), 1L);
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Looking up @Streamer on YouTube...", a.addIgnore("yt:@Streamer"));
        host.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("Added @Streamer (YouTube) to the ignore list."), a.lastLookupMessage());
        assertTrue(host.config.ignoredViewers().contains(YT));
        assertEquals(0, host.roster.size());
    }

    @Test void lookupFailuresReportEachKindAndChangeNothing() {
        Map<Throwable, String> cases = new java.util.LinkedHashMap<>();
        cases.put(new YouTubeException(YouTubeException.Kind.NOT_FOUND, "x"), "No YouTube channel @Streamer found.");
        cases.put(new YouTubeException(YouTubeException.Kind.TRANSIENT, "x"), "YouTube lookup failed; try again.");
        cases.put(new YouTubeException(YouTubeException.Kind.KEY_INVALID, "x"), "YouTube API key rejected.");
        cases.put(new YouTubeException(YouTubeException.Kind.QUOTA_EXCEEDED, "x"),
            "YouTube quota used up for today; paste the channel link instead.");
        cases.put(new YouTubeException(YouTubeException.Kind.REJECTED, "x"), "YouTube lookup failed; try again.");
        cases.put(new ActionsHost.NoYouTubeKey(), NO_KEY);
        cases.put(new ActionsHost.LookupUnavailable(), "YouTube lookup unavailable (server stopping).");
        cases.put(new IllegalStateException("anything else"), "YouTube lookup failed; try again.");
        cases.put(new java.util.concurrent.CompletionException(new YouTubeException(YouTubeException.Kind.NOT_FOUND, "x")),
            "No YouTube channel @Streamer found.");
        cases.put(new RuntimeException("boom"), "YouTube lookup failed; try again.");
        for (Map.Entry<Throwable, String> c : cases.entrySet()) {
            FakeHost host = withKey();
            SproutwatchActions a = new SproutwatchActions(host);
            a.addIgnore("yt:@Streamer");
            host.lookupFutures.get(0).completeExceptionally(c.getKey());
            assertEquals(Optional.of(c.getValue()), a.lastLookupMessage(), c.getKey().toString());
            assertFalse(a.lastLookupMessage().get().contains(KEY));
            assertFalse(host.config.ignoredViewers().stream().anyMatch(k -> k.startsWith("yt:")));
            assertTrue(host.config.youTubeLabels().isEmpty());
            assertEquals(0, host.saveCalls);
            assertEquals(1, host.changedCalls, "the page refreshes to show the outcome");
        }
    }

    // ---- removal -----------------------------------------------------------------------------

    @Test void removeByStoredLabelNeedsNoNetwork() {
        FakeHost host = new FakeHost();   // no key: the label alone is enough
        host.config.addIgnore(YT);
        host.config.setYouTubeLabel(YT, "@Streamer");
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Removed @Streamer (YouTube) from the ignore list.", a.removeIgnore("yt:@streamer"));
        assertTrue(host.lookups.isEmpty());
        assertFalse(host.config.ignoredViewers().contains(YT));
        assertTrue(host.config.youTubeLabel(YT).isEmpty(), "label cleaned up");
        assertEquals(1, host.saveCalls);
    }

    @Test void removeByLabelKeepsTheLabelWhileTheOtherListStillHasIt() {
        FakeHost host = new FakeHost();
        host.config.addIgnore(YT);
        host.config.addAllow(YT);
        host.config.setYouTubeLabel(YT, "@Streamer");
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Removed @Streamer (YouTube) from the allow list.", a.removeAllow("youtube.com/@Streamer"));
        assertEquals(Optional.of("@Streamer"), host.config.youTubeLabel(YT));
        assertEquals("@Streamer (YouTube) is not on the allow list.", a.removeAllow("yt:@Streamer"));
    }

    @Test void removeByUnknownHandleLooksItUp() {
        FakeHost host = withKey();
        host.config.addAllow(YT);
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Looking up @Streamer on YouTube...", a.removeAllow("yt:@Streamer"));
        assertEquals(List.of("@Streamer"), host.lookups);
        host.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("Removed @Streamer (YouTube) from the allow list."), a.lastLookupMessage());
        assertTrue(host.config.allowedViewers().isEmpty());
        assertEquals(1, host.saveCalls);
        assertEquals(1, host.changedCalls);

        a.removeAllow("yt:@Other");
        host.lookupFutures.get(1).complete("UCzzzzzzzzzzzzzzzzzzzzzz");
        assertEquals(Optional.of("@Other (YouTube) is not on the allow list."), a.lastLookupMessage());
        assertEquals(1, host.saveCalls);
    }

    @Test void invalidYouTubeInputIsRejected() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        String watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        assertEquals(ViewerEntry.INVALID_YOUTUBE, a.addAllow(watch));
        assertEquals(ViewerEntry.INVALID_YOUTUBE, a.removeIgnore("yt:!!"));
        assertTrue(host.lookups.isEmpty());
        assertEquals(0, host.saveCalls);
    }

    @Test void twitchFormsAreUnchanged() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Added alice to the allow list.", a.addAllow("@Alice"));
        assertEquals("Added bob to the ignore list.", a.addIgnore("Bob"));
        assertTrue(host.lookups.isEmpty(), "bare @name is Twitch, never a YouTube lookup");
    }

    // ---- review fixes ------------------------------------------------------------------------

    @Test void commandCallerIsToldTheOutcome() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        List<String> told = new java.util.ArrayList<>();
        assertEquals("Looking up @Streamer on YouTube...", a.addIgnore("yt:@Streamer", told::add));
        assertTrue(told.isEmpty(), "nothing until the lookup ends");
        host.lookupFutures.get(0).complete(CH);
        assertEquals(List.of("Added @Streamer (YouTube) to the ignore list."), told);
        assertEquals(Optional.of("Added @Streamer (YouTube) to the ignore list."), a.lastLookupMessage(), "the page still gets it");
        assertEquals(1, host.changedCalls);

        a.removeAllow("yt:@Ghost", told::add);
        host.lookupFutures.get(1).completeExceptionally(new YouTubeException(YouTubeException.Kind.NOT_FOUND, "x"));
        assertEquals("No YouTube channel @Ghost found.", told.get(1));

        assertEquals("Added @Streamer (YouTube) to the allow list.", a.addAllow(CH, told::add), "the known label is shown");
        assertEquals(2, told.size(), "immediate adds answer in the reply only");
    }

    @Test void aThrowingCallbackDoesNotBreakTheLookup() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        a.addAllow("yt:@Streamer", message -> { throw new IllegalStateException("player left"); });
        host.lookupFutures.get(0).complete(CH);
        assertEquals(Set.of(YT), host.config.allowedViewers());
        assertEquals(1, host.changedCalls);
    }

    @Test void successfulLookupRefreshesTheLabelOfAnExistingEntry() {
        FakeHost host = withKey();
        host.config.addAllow(YT);
        host.config.setYouTubeLabel(YT, "@oldname");
        SproutwatchActions a = new SproutwatchActions(host);
        a.addAllow("yt:@NewName");
        host.lookupFutures.get(0).complete(CH);
        assertEquals(Optional.of("@NewName"), host.config.youTubeLabel(YT));
        assertEquals(Optional.of("@NewName (YouTube) is already on the allow list."), a.lastLookupMessage());
        assertEquals(1, host.saveCalls, "the refreshed label is saved");
    }

    @Test void atMostFiveLookupsAreInFlight() {
        FakeHost host = withKey();
        SproutwatchActions a = new SproutwatchActions(host);
        for (int i = 0; i < SproutwatchActions.MAX_PENDING_LOOKUPS; i++) {
            assertEquals("Looking up @user" + i + " on YouTube...", a.addIgnore("yt:@user" + i));
        }
        assertEquals(SproutwatchActions.TOO_MANY_REPLY, a.addIgnore("yt:@onemore"));
        assertEquals(5, host.lookups.size());
        host.lookupFutures.get(0).completeExceptionally(new YouTubeException(YouTubeException.Kind.TRANSIENT, "x"));
        assertEquals("Looking up @onemore on YouTube...", a.addIgnore("yt:@onemore"), "a finished lookup frees a slot");
        assertEquals(6, host.lookups.size());
    }
}
