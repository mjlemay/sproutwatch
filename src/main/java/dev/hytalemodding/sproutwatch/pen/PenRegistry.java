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
 * Bookkeeping of live sprouts: login -> Entry. Plain synchronized map (no listeners in v1).
 * Ref&lt;EntityStore&gt; is an identity key (no equals/hashCode override), the same assumption
 * Subinator's BossRegistry relies on: the engine hands back the same Ref instance in the spawn
 * callback and in system callbacks. No store access happens here.
 * <p>
 * Also tracks <em>retired</em> logins: viewers whose sprout a player killed. A retired login gets no
 * sprout again until it drops out of the roster (see {@link #retainRetired}) or {@link #clear} runs.
 */
public final class PenRegistry {

    /** One live sprout. networkId is -1 when the entity had no NetworkId at spawn time. */
    public record Entry(String login, Ref<EntityStore> ref, int networkId, UUID worldUuid,
                        long spawnedAt, long lastSeen) {
        public Entry withLastSeen(long t) {
            return new Entry(login, ref, networkId, worldUuid, spawnedAt, t);
        }
    }

    private final Map<String, Entry> byLogin = new HashMap<>();
    private final Map<Ref<EntityStore>, String> byRef = new HashMap<>();
    private final Set<String> retired = new HashSet<>();

    public synchronized void put(Entry entry) {
        Entry previous = byLogin.put(entry.login(), entry);
        if (previous != null) byRef.remove(previous.ref());
        byRef.put(entry.ref(), entry.login());
    }

    public synchronized Entry get(String login) {
        return byLogin.get(login);
    }

    public synchronized boolean contains(String login) {
        return byLogin.containsKey(login);
    }

    public synchronized Entry remove(String login) {
        Entry e = byLogin.remove(login);
        if (e != null) byRef.remove(e.ref());
        return e;
    }

    /** For PenDespawnSystem: the entity vanished for any reason. Null if ref is not ours. */
    public synchronized Entry removeByRef(Ref<EntityStore> ref) {
        if (ref == null) return null;
        String login = byRef.remove(ref);
        return login == null ? null : byLogin.remove(login);
    }

    /** Read-only lookup by entity ref (read-only lookup for callers that must not evict, e.g. diagnostics). Null if ref is null or not ours. */
    public synchronized Entry findByRef(Ref<EntityStore> ref) {
        if (ref == null) return null;
        String login = byRef.get(ref);
        return login == null ? null : byLogin.get(login);
    }

    /** A player killed this viewer's sprout: no sprout again while they stay in chat. */
    public synchronized void retire(String login) {
        retired.add(login);
    }

    public synchronized boolean isRetired(String login) {
        return retired.contains(login);
    }

    /** Immutable point-in-time copy of the retired logins. */
    public synchronized Set<String> retiredLogins() {
        return Set.copyOf(retired);
    }

    /**
     * Called each tick with the roster so a viewer who leaves chat is un-retired: drops every retired
     * login that is not in {@code present}.
     */
    public synchronized void retainRetired(Set<String> present) {
        retired.retainAll(present);
    }

    /** Marks a login as present at time now; unknown logins are ignored. */
    public synchronized void touch(String login, long now) {
        Entry e = byLogin.get(login);
        if (e != null) byLogin.put(login, e.withLastSeen(now));
    }

    /** login -> lastSeen, the shape PenReconciler wants. */
    public synchronized Map<String, Long> lastSeenMap() {
        Map<String, Long> out = new HashMap<>();
        for (Entry e : byLogin.values()) out.put(e.login(), e.lastSeen());
        return out;
    }

    public synchronized int size() {
        return byLogin.size();
    }

    public synchronized boolean isEmpty() {
        return byLogin.isEmpty();
    }

    /** Immutable point-in-time copy. */
    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(byLogin.values()));
    }

    /** Empties the registry (live entries and retired logins) and returns the live entries (for /sproutwatch clear). */
    public synchronized List<Entry> clear() {
        List<Entry> out = List.copyOf(new ArrayList<>(byLogin.values()));
        byLogin.clear();
        byRef.clear();
        retired.clear();
        return out;
    }
}
