package dev.hytalemodding.sproutwatch.pen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure diff between chat and pen.
 *   roster: viewer key -> firstSeen (ordering only; presence = key present)
 *   pen:    viewer key -> lastSeen  (PenTicker sets this to now for every eligible viewer key still in the roster)
 * Default (persist off):
 *   despawn = pen viewer keys whose lastSeen is older than graceMillis.
 *   spawn   = if the pen has room after despawns: the first viewer key in priority (the "!sprout" queue,
 *             FIFO) that is in the roster and not in the pen; otherwise the roster viewer key not in the
 *             pen with the smallest firstSeen (ties by viewer key), so nobody waits forever.
 * Persist on: grace is ignored and sprouts outlive their viewer. The spawn candidate is chosen the
 * same way; below the cap it simply spawns. At the cap, the pen viewer key NOT in the roster with the
 * smallest lastSeen (the viewer gone the longest; ties by viewer key) is despawned to make room, one swap
 * per tick. If every sprout's viewer is still present, nothing happens and the candidate waits.
 */
public final class PenReconciler {

    private PenReconciler() {}

    /** No priority: first-seen order only. */
    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen,
                                    int cap, long graceMillis, long now) {
        return reconcile(roster, pen, List.of(), cap, graceMillis, now);
    }

    /** Grace-based despawn (persist off). */
    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen, List<String> priority,
                                    int cap, long graceMillis, long now) {
        return reconcile(roster, pen, priority, cap, graceMillis, now, false);
    }

    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen, List<String> priority,
                                    int cap, long graceMillis, long now, boolean persist) {
        return reconcile(roster, pen, priority, cap, graceMillis, now, persist, Set.of());
    }

    /**
     * {@code guests} are /sproutwatch test viewer keys: present, but at the cap (persist) a guest in the pen
     * is replaced before any longest-gone absent viewer, and never to make room for another guest.
     */
    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen, List<String> priority,
                                    int cap, long graceMillis, long now, boolean persist, Set<String> guests) {
        return reconcile(roster, pen, priority, cap, graceMillis, now, persist, guests, Map.of(), 0L);
    }

    /**
     * Quiet timeout: when the pen is full and the candidate came from the queue ({@code priority}),
     * and nothing else can be given up, the present pen viewer whose last chat message
     * ({@code lastActive}) is oldest is replaced if they have been quiet for at least
     * {@code quietMillis} (0 disables). Viewers next in plain first-seen order never evict anyone.
     */
    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen, List<String> priority,
                                    int cap, long graceMillis, long now, boolean persist, Set<String> guests,
                                    Map<String, Long> lastActive, long quietMillis) {
        List<String> despawn = new ArrayList<>();
        if (!persist) {
            long cutoff = now - graceMillis;
            for (Map.Entry<String, Long> e : pen.entrySet()) {
                if (e.getValue() < cutoff) despawn.add(e.getKey());
            }
            despawn.sort(Comparator.naturalOrder());
        }

        boolean room = pen.size() - despawn.size() < cap;
        Optional<String> spawn = candidate(roster, pen, priority);
        if (room) return new PenPlan(spawn, List.copyOf(despawn));

        boolean queued = spawn.isPresent() && priority.contains(spawn.get());
        if (!persist) {
            // Persist off: present viewers are never evicted, except for a queued viewer by quiet timeout.
            Optional<String> quiet = queued ? quietest(pen, roster, guests, lastActive, now, quietMillis) : Optional.empty();
            quiet.ifPresent(despawn::add);
            return new PenPlan(quiet.isPresent() ? spawn : Optional.empty(), List.copyOf(despawn));
        }

        if (spawn.isPresent()) {
            // At the cap: give up a guest first (a real viewer is waiting), else the sprout whose
            // viewer has been gone the longest, if any.
            Optional<String> victim = guests.contains(spawn.get()) ? Optional.empty()
                : pen.keySet().stream().filter(guests::contains).sorted().findFirst();
            if (victim.isEmpty()) {
                victim = pen.entrySet().stream()
                    .filter(e -> !roster.containsKey(e.getKey()))
                    .min(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey);
            }
            if (victim.isEmpty() && queued) victim = quietest(pen, roster, guests, lastActive, now, quietMillis);
            if (victim.isPresent()) {
                despawn.add(victim.get());
            } else {
                spawn = Optional.empty();
            }
        }
        return new PenPlan(spawn, List.copyOf(despawn));
    }

    /** The present, non-guest pen viewer quiet the longest, if quiet for at least quietMillis (0: never). */
    private static Optional<String> quietest(Map<String, Long> pen, Map<String, Long> roster, Set<String> guests,
                                             Map<String, Long> lastActive, long now, long quietMillis) {
        if (quietMillis <= 0) return Optional.empty();
        return pen.keySet().stream()
            .filter(l -> roster.containsKey(l) && !guests.contains(l))
            .filter(l -> now - lastActive.getOrDefault(l, now) >= quietMillis)
            .min(Comparator.comparing((String l) -> lastActive.getOrDefault(l, now)).thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Which sprouts to remove right now so the pen holds at most {@code cap}: absent viewers' sprouts
     * first (viewer gone the longest first), then present viewers' sprouts newest arrival first; ties
     * by viewer key. Used when MaxSprouts is lowered below the pen count. {@code pen} is viewer key -> lastSeen,
     * {@code roster} is viewer key -> firstSeen (present viewers).
     */
    public static List<String> trim(Map<String, Long> pen, Map<String, Long> roster, int cap) {
        return trim(pen, roster, cap, Set.of());
    }

    /** As above; guests (test viewers) go before anyone else. */
    public static List<String> trim(Map<String, Long> pen, Map<String, Long> roster, int cap, Set<String> guests) {
        int surplus = pen.size() - Math.max(1, cap);
        if (surplus <= 0) return List.of();
        List<String> guestsInPen = pen.keySet().stream().filter(guests::contains).sorted().toList();
        List<String> absent = pen.entrySet().stream()
            .filter(e -> !roster.containsKey(e.getKey()) && !guests.contains(e.getKey()))
            .sorted(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey).toList();
        List<String> present = pen.keySet().stream()
            .filter(l -> roster.containsKey(l) && !guests.contains(l))
            .sorted(Comparator.comparing((String l) -> roster.get(l)).reversed().thenComparing(Comparator.naturalOrder()))
            .toList();
        List<String> victims = new ArrayList<>(guestsInPen);
        victims.addAll(absent);
        victims.addAll(present);
        return List.copyOf(victims.subList(0, surplus));
    }

    /** First eligible priority viewer key, else the earliest-seen roster viewer key not yet in the pen. */
    private static Optional<String> candidate(Map<String, Long> roster, Map<String, Long> pen, List<String> priority) {
        for (String viewerKey : priority) {
            if (roster.containsKey(viewerKey) && !pen.containsKey(viewerKey)) return Optional.of(viewerKey);
        }
        return roster.entrySet().stream()
            .filter(e -> !pen.containsKey(e.getKey()))
            .min(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey);
    }
}
