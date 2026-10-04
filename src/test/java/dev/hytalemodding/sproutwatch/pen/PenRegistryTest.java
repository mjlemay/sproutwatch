package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PenRegistryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final AtomicInteger nextIndex = new AtomicInteger();

    /** Null-store Ref: identity token only. toString overridden because Ref's own dereferences the store. */
    private static final class TestRef extends Ref<EntityStore> {
        TestRef(int index) { super(null, index); }
        @Override public String toString() { return "TestRef@" + System.identityHashCode(this); }
    }

    private static Ref<EntityStore> ref() { return new TestRef(nextIndex.getAndIncrement()); }

    private static PenRegistry.Entry entry(String viewerKey, Ref<EntityStore> ref, long timestamp) {
        return new PenRegistry.Entry(viewerKey, ref, 7, WORLD, timestamp, timestamp);
    }

    @Test void putGetRemoveByViewerKey() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        assertTrue(r.contains("alice"));
        assertSame(a, r.get("alice").ref());
        assertEquals(1, r.size());
        PenRegistry.Entry removed = r.remove("alice");
        assertEquals("alice", removed.viewerKey());
        assertNull(r.remove("alice"));
        assertTrue(r.isEmpty());
        // old ref must no longer resolve, even after alice rejoins with a fresh ref
        r.put(entry("alice", ref(), 200));
        assertNull(r.removeByReference(a));
        assertTrue(r.contains("alice"));
    }

    @Test void removeByRefFindsTheViewerKey() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        r.put(entry("bob", ref(), 100));
        assertEquals("alice", r.removeByReference(a).viewerKey());
        assertNull(r.removeByReference(a));
        assertNull(r.removeByReference(ref()));
        assertEquals(1, r.size());
        assertTrue(r.contains("bob"));
    }

    @Test void putReplacesAndReindexesRef() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> old = ref();
        Ref<EntityStore> fresh = ref();
        r.put(entry("alice", old, 100));
        r.put(entry("alice", fresh, 200));
        assertEquals(1, r.size());
        assertNull(r.removeByReference(old));
        assertEquals("alice", r.removeByReference(fresh).viewerKey());
    }

    @Test void touchUpdatesLastSeenOnlyForKnownViewers() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        r.touch("alice", 500);
        r.touch("ghost", 500);
        assertEquals(Map.of("alice", 500L), r.lastSeenMap());
        assertEquals(100L, r.get("alice").spawnedAt());
        assertSame(a, r.get("alice").ref());
        assertEquals(7, r.get("alice").networkId());
    }

    @Test void snapshotAndClear() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> b = ref();
        r.put(entry("b", b, 1));
        r.put(entry("a", ref(), 2));
        List<PenRegistry.Entry> snap = r.snapshot();
        assertEquals(2, snap.size());
        assertThrows(UnsupportedOperationException.class, () -> snap.add(entry("c", ref(), 3)));
        List<PenRegistry.Entry> cleared = r.clear();
        assertEquals(2, cleared.size());
        assertTrue(r.isEmpty());
        r.put(entry("b", ref(), 3));
        assertNull(r.removeByReference(b));
        assertTrue(r.contains("b"));
    }

    @Test void findByRefReturnsEntryWithoutRemoving() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        PenRegistry.Entry found = r.findByReference(a);
        assertNotNull(found);
        assertEquals("alice", found.viewerKey());
        assertSame(a, found.ref());
        assertTrue(r.contains("alice"));
        assertEquals(1, r.size());
        assertNull(r.findByReference(ref()));
        assertNull(r.findByReference(null));
    }

    @Test void retireIsSetLikeAndPrunedByRetain() {
        PenRegistry r = new PenRegistry();
        assertFalse(r.isRetired("a"));
        r.retire("a");
        r.retire("b");
        r.retire("a"); // idempotent
        assertTrue(r.isRetired("a"));
        assertTrue(r.isRetired("b"));
        assertFalse(r.isRetired("c"));
        assertEquals(Set.of("a", "b"), r.retiredViewers());
        r.retainRetired(Set.of("a", "zzz"));
        assertEquals(Set.of("a"), r.retiredViewers());
        assertFalse(r.isRetired("b"));
        Set<String> snap = r.retiredViewers();
        assertThrows(UnsupportedOperationException.class, () -> snap.add("x"));
        r.retire("d");
        assertEquals(Set.of("a"), snap, "retiredViewers is a copy, not a live view");
    }

    @Test void clearAlsoDropsRetired() {
        PenRegistry r = new PenRegistry();
        r.put(entry("alice", ref(), 1));
        r.retire("alice");
        r.retire("bob");
        assertEquals(1, r.clear().size());
        assertTrue(r.isEmpty());
        assertFalse(r.isRetired("alice"));
        assertFalse(r.isRetired("bob"));
        assertTrue(r.retiredViewers().isEmpty());
    }
}
