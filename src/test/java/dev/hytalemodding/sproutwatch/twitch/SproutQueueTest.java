package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SproutQueueTest {

    @Test void offerKeepsFifoOrder() {
        SproutQueue q = new SproutQueue();
        assertTrue(q.offer("b"));
        assertTrue(q.offer("a"));
        assertTrue(q.offer("c"));
        assertEquals(List.of("b", "a", "c"), q.snapshot());
        assertEquals(3, q.size());
    }

    @Test void duplicateAndBlankOffersAreIgnored() {
        SproutQueue q = new SproutQueue();
        assertTrue(q.offer("a"));
        assertFalse(q.offer("a"));
        assertFalse(q.offer(""));
        assertFalse(q.offer("   "));
        assertFalse(q.offer(null));
        assertEquals(List.of("a"), q.snapshot());
    }

    @Test void removeReportsWhetherPresent() {
        SproutQueue q = new SproutQueue();
        q.offer("a");
        q.offer("b");
        assertTrue(q.contains("a"));
        assertTrue(q.remove("a"));
        assertFalse(q.remove("a"));
        assertFalse(q.contains("a"));
        assertEquals(List.of("b"), q.snapshot());
    }

    @Test void retainDropsLoginsNotPresent() {
        SproutQueue q = new SproutQueue();
        q.offer("a");
        q.offer("b");
        q.offer("c");
        q.retain(Set.of("c", "a", "zzz"));
        assertEquals(List.of("a", "c"), q.snapshot());
    }

    @Test void clearEmpties() {
        SproutQueue q = new SproutQueue();
        q.offer("a");
        q.clear();
        assertEquals(0, q.size());
        assertEquals(List.of(), q.snapshot());
    }

    @Test void snapshotIsImmutableAndDetached() {
        SproutQueue q = new SproutQueue();
        q.offer("a");
        List<String> snap = q.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.add("b"));
        q.offer("b");
        assertEquals(List.of("a"), snap);
    }
}
