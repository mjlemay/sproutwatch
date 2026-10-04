package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bookkeeping of live sprouts: viewer key -> Entry. Plain synchronized map.
 * {@code Ref<EntityStore>} is an identity key (no equals/hashCode override). We rely on the engine
 * handing back the same Ref instance in the spawn callback and in system callbacks. No store access happens here.
 *
 * Also tracks retired viewer keys: viewers whose sprout a player killed. A retired viewer key gets no
 * sprout again until it drops out of the roster (see {@link #retainRetired}) or {@link #clear} runs.
 */
public final class PenRegistry {

    /** One live sprout. networkId is -1 when the entity had no NetworkId at spawn time. */
    public record Entry(String viewerKey, Ref<EntityStore> ref, int networkId, UUID worldUuid,
                        long spawnedAt, long lastSeen) {
        public Entry withLastSeen(long seenAt) {
            return new Entry(viewerKey, ref, networkId, worldUuid, spawnedAt, seenAt);
        }
    }

    private final Map<String, Entry> byViewerKey = new HashMap<>();
    private final Map<Ref<EntityStore>, String> byReference = new HashMap<>();
    private final Set<String> retired = new HashSet<>();

    public synchronized void put(Entry entry) {
        Entry previous = byViewerKey.put(entry.viewerKey(), entry);
        if (previous != null) byReference.remove(previous.ref());
        byReference.put(entry.ref(), entry.viewerKey());
    }

    public synchronized Entry get(String viewerKey) {
        return byViewerKey.get(viewerKey);
    }

    public synchronized boolean contains(String viewerKey) {
        return byViewerKey.containsKey(viewerKey);
    }

    public synchronized Entry remove(String viewerKey) {
        Entry entry = byViewerKey.remove(viewerKey);
        if (entry != null) byReference.remove(entry.ref());
        return entry;
    }

    /** For PenDespawnSystem: the entity vanished for any reason. Null if ref is not ours. */
    public synchronized Entry removeByReference(Ref<EntityStore> ref) {
        if (ref == null) return null;
        String viewerKey = byReference.remove(ref);
        return viewerKey == null ? null : byViewerKey.remove(viewerKey);
    }

    /** Read-only lookup by entity ref (read-only lookup for callers that must not evict, e.g. diagnostics). Null if ref is null or not ours. */
    public synchronized Entry findByReference(Ref<EntityStore> ref) {
        if (ref == null) return null;
        String viewerKey = byReference.get(ref);
        return viewerKey == null ? null : byViewerKey.get(viewerKey);
    }

    /** A player killed this viewer's sprout: no sprout again while they stay in chat. */
    public synchronized void retire(String viewerKey) {
        retired.add(viewerKey);
    }

    public synchronized boolean isRetired(String viewerKey) {
        return retired.contains(viewerKey);
    }

    /** Immutable point-in-time copy of the retired viewer keys. */
    public synchronized Set<String> retiredViewers() {
        return Set.copyOf(retired);
    }

    /**
     * Called each tick with the roster so a viewer who leaves chat is un-retired: drops every retired
     * viewer key that is not in {@code present}.
     */
    public synchronized void retainRetired(Set<String> present) {
        retired.retainAll(present);
    }

    /** Marks a viewer key as present at time now; unknown viewer keys are ignored. */
    public synchronized void touch(String viewerKey, long now) {
        Entry entry = byViewerKey.get(viewerKey);
        if (entry != null) byViewerKey.put(viewerKey, entry.withLastSeen(now));
    }

    /** viewer key -> lastSeen, the shape PenReconciler wants. */
    public synchronized Map<String, Long> lastSeenMap() {
        Map<String, Long> out = new HashMap<>();
        for (Entry e : byViewerKey.values()) out.put(e.viewerKey(), e.lastSeen());
        return out;
    }

    public synchronized int size() {
        return byViewerKey.size();
    }

    public synchronized boolean isEmpty() {
        return byViewerKey.isEmpty();
    }

    /** Immutable point-in-time copy. */
    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(byViewerKey.values()));
    }

    /** Empties the registry (live entries and retired viewer keys) and returns the live entries (for /sproutwatch clear). */
    public synchronized List<Entry> clear() {
        List<Entry> out = List.copyOf(new ArrayList<>(byViewerKey.values()));
        byViewerKey.clear();
        byReference.clear();
        retired.clear();
        return out;
    }
}
