package dev.hytalemodding.sproutwatch.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatRosterTest {

    private static ChatRoster roster(String... ignored) {
        return new ChatRoster(() -> Set.of(ignored));
    }

    @Test void guestsArePresentUntilForgotten() {
        ChatRoster r = roster();
        r.addGuest("alice", 0L);
        assertEquals(Map.of("alice", 0L), r.snapshot());
        assertEquals(Set.of("alice"), r.guests());
        r.apply(new RosterEvent.Join("bob"), 5L);
        assertEquals(Set.of("alice"), r.guests(), "real viewers are not guests");
        assertTrue(r.removeGuest("alice"));
        assertFalse(r.removeGuest("alice"));
        assertEquals(Map.of("bob", 5L), r.snapshot());
    }

    @Test void forgetGuestsDropsOnlyGuestsAndPartDropsTheGuestFlag() {
        ChatRoster r = roster();
        r.addGuest("alice", 0L);
        r.addGuest("carol", 0L);
        r.apply(new RosterEvent.Join("bob"), 5L);
        r.apply(new RosterEvent.Part("carol"), 6L);
        assertEquals(Set.of("alice"), r.guests());
        assertEquals(List.of("alice"), r.forgetGuests());
        assertEquals(Map.of("bob", 5L), r.snapshot());
        assertEquals(Set.of(), r.guests());
        r.addGuest("alice", 0L);
        r.clear();
        assertEquals(Set.of(), r.guests(), "clear forgets guests too");
    }

    @Test void lastActiveFollowsChatMessagesNotPresence() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Join("alice"), 10L);
        r.apply(new RosterEvent.Names(List.of("bob")), 20L);
        assertEquals(Map.of("alice", 10L, "bob", 20L), r.lastActiveMap(), "join/names count as initial activity");
        r.apply(new RosterEvent.Join("alice"), 30L);
        assertEquals(10L, r.lastActiveMap().get("alice"), "a repeat join is not activity");
        r.apply(new RosterEvent.Chat("alice", "hi"), 40L);
        assertEquals(40L, r.lastActiveMap().get("alice"), "a message is");
        r.addGuest("g", 0L);
        assertEquals(0L, r.lastActiveMap().get("g"));
        r.apply(new RosterEvent.Part("bob"), 50L);
        r.removeGuest("g");
        assertEquals(Map.of("alice", 40L), r.lastActiveMap());
        r.clear();
        assertEquals(Map.of(), r.lastActiveMap());
    }

    @Test void chatAfterJoinKeepsFirstSeenAndMovesLastActive() {
        ChatRoster roster = roster();
        roster.apply(new RosterEvent.Join("alice"), 10L);
        roster.apply(new RosterEvent.Chat("alice", "hi"), 25L);
        assertEquals(Map.of("alice", 10L), roster.snapshot());
        assertEquals(Map.of("alice", 25L), roster.lastActiveMap());
    }

    @Test void repeatedJoinResetsNeitherTimestamp() {
        ChatRoster roster = roster();
        roster.apply(new RosterEvent.Join("alice"), 10L);
        roster.apply(new RosterEvent.Chat("alice", "hi"), 20L);
        roster.apply(new RosterEvent.Join("alice"), 30L);
        roster.apply(new RosterEvent.Names(List.of("alice")), 40L);
        assertEquals(Map.of("alice", 10L), roster.snapshot());
        assertEquals(Map.of("alice", 20L), roster.lastActiveMap());
    }

    @Test void partRemovesTheViewerFromBothViews() {
        ChatRoster roster = roster();
        roster.apply(new RosterEvent.Join("alice"), 10L);
        roster.apply(new RosterEvent.Chat("alice", "hi"), 20L);
        roster.apply(new RosterEvent.Part("alice"), 30L);
        assertEquals(Map.of(), roster.snapshot());
        assertEquals(Map.of(), roster.lastActiveMap());
        assertEquals(0, roster.size());
    }

    @Test void snapshotAndLastActiveMapHoldTheSameKeysAfterMixedEvents() {
        ChatRoster roster = roster("nightbot");
        roster.apply(new RosterEvent.Names(List.of("alice", "bob", "nightbot", "justinfan7")), 1L);
        roster.apply(new RosterEvent.Join("carol"), 2L);
        roster.apply(new RosterEvent.Chat("dave", "hello"), 3L);
        roster.apply(new RosterEvent.Chat("alice", "hi"), 4L);
        roster.apply(new RosterEvent.Part("bob"), 5L);
        roster.addGuest("guest", 0L);
        roster.apply(new RosterEvent.Chat("nightbot", "!sprout"), 6L);
        roster.addGuest("other guest", 0L);
        roster.removeGuest("other guest");
        Set<String> expected = Set.of("alice", "carol", "dave", "guest");
        assertEquals(expected, roster.snapshot().keySet());
        assertEquals(expected, roster.lastActiveMap().keySet());
        assertEquals(expected.size(), roster.size());
    }

    @Test void namesJoinAndSeenAddPartRemoves() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Names(List.of("a", "b")), 100);
        r.apply(new RosterEvent.Join("c"), 200);
        r.apply(new RosterEvent.Chat("d", "hi"), 300);
        r.apply(new RosterEvent.Part("b"), 400);
        assertEquals(Map.of("a", 100L, "c", 200L, "d", 300L), r.snapshot());
        assertEquals(3, r.size());
    }

    @Test void firstSightingWinsUntilTheyLeave() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Join("a"), 100);
        r.apply(new RosterEvent.Chat("a", "hi"), 200);
        r.apply(new RosterEvent.Names(List.of("a")), 300);
        assertEquals(100L, r.snapshot().get("a"));
        r.apply(new RosterEvent.Part("a"), 400);
        r.apply(new RosterEvent.Join("a"), 500);
        assertEquals(500L, r.snapshot().get("a"));
    }

    @Test void dropsIgnoredAndJustinfanLogins() {
        ChatRoster r = roster("nightbot");
        r.apply(new RosterEvent.Names(List.of("nightbot", "justinfan12345", "real")), 1);
        r.apply(new RosterEvent.Join("NightBot".toLowerCase()), 2);
        r.apply(new RosterEvent.Chat("justinfan9", "hi"), 3);
        assertEquals(Set.of("real"), r.snapshot().keySet());
    }

    @Test void snapshotIsImmutableAndClearEmpties() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Join("a"), 1);
        Map<String, Long> snap = r.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.put("b", 2L));
        r.clear();
        assertEquals(0, r.size());
        assertEquals(1, snap.size());
    }

    @Test void nullEventIsIgnored() {
        ChatRoster r = roster();
        r.apply(null, 1);
        assertEquals(0, r.size());
    }

    private static ChatRoster queued(String... ignored) {
        return new ChatRoster(() -> Set.of(ignored), () -> "!sprout", new SproutQueue());
    }

    @Test void chatCommandQueuesTheViewerOnce() {
        ChatRoster r = queued();
        r.apply(new RosterEvent.Chat("a", "!sprout"), 1);
        r.apply(new RosterEvent.Chat("a", "!sprout"), 2);
        r.apply(new RosterEvent.Chat("b", "hello"), 3);
        r.apply(new RosterEvent.Chat("c", "!SPROUT "), 4);
        assertEquals(List.of("a", "c"), r.queue().snapshot());
        assertEquals(Set.of("a", "b", "c"), r.snapshot().keySet());
    }

    @Test void ignoredViewersNeverQueue() {
        ChatRoster r = queued("nightbot");
        r.apply(new RosterEvent.Chat("nightbot", "!sprout"), 1);
        r.apply(new RosterEvent.Chat("justinfan1", "!sprout"), 2);
        assertEquals(List.of(), r.queue().snapshot());
    }

    @Test void partRemovesFromQueue() {
        ChatRoster r = queued();
        r.apply(new RosterEvent.Chat("a", "!sprout"), 1);
        r.apply(new RosterEvent.Part("a"), 2);
        assertEquals(List.of(), r.queue().snapshot());
        assertEquals(0, r.size());
    }

    @Test void clearEmptiesQueue() {
        ChatRoster r = queued();
        r.apply(new RosterEvent.Chat("a", "!sprout"), 1);
        r.clear();
        assertEquals(0, r.queue().size());
    }

    @Test void convenienceConstructorDefaultsToSproutCommand() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Chat("a", "!sprout"), 1);
        assertEquals(List.of("a"), r.queue().snapshot());
    }

    /** A yt: key on IgnoreUsers never enters the roster when the YouTube source applies its Chat event. */
    @Test void ignoredYouTubeChatterNeverEntersTheRoster() {
        dev.hytalemodding.sproutwatch.config.SproutwatchConfig config =
            new dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess().fresh();
        String ignoredKey = dev.hytalemodding.sproutwatch.chat.ViewerKey.youtube("UCabcdefghijklmnopqrstuV");
        String otherKey = dev.hytalemodding.sproutwatch.chat.ViewerKey.youtube("UCabcdefghijklmnopqrstuW");
        assertTrue(config.addIgnore(ignoredKey));
        ChatRoster r = new ChatRoster(config::ignoredViewers, config::getQueueCommand, new SproutQueue());
        r.apply(new RosterEvent.Chat(ignoredKey, "!sprout"), 1L);
        r.apply(new RosterEvent.Chat(otherKey, "hi"), 2L);
        assertEquals(Set.of(otherKey), r.snapshot().keySet());
        assertEquals(0, r.queue().size(), "the ignored chatter's !sprout is not queued");
    }
}
