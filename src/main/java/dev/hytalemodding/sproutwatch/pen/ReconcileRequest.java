package dev.hytalemodding.sproutwatch.pen;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything one {@link PenReconciler#reconcile(ReconcileRequest)} call looks at. Build it with
 * {@link #builder(Map, Map, int, long)} so the two time spans are set by name and cannot be swapped
 * with {@code now}; the builder holds the only defaults.
 *
 * The collections are copied, so the request is a fixed point-in-time view. The reconciler never
 * depends on map iteration order (every pick is a sort or a minimum with a viewer key tie-break)
 * and the queue keeps its order, so copying does not change any plan.
 *
 * @param roster      viewer key -> firstSeen for every eligible viewer in chat (presence = key present)
 * @param pen         viewer key -> lastSeen for every sprout in the pen
 * @param queue       the "!sprout" queue, first in first out; served before first-seen order
 * @param cap         most sprouts the pen may hold (MaxSprouts)
 * @param graceMillis how long a sprout outlives its viewer when persist is off
 * @param now         the current time; must be the same value PenTicker passed to touch
 * @param persist     PersistSprouts: grace is ignored and sprouts outlive their viewer
 * @param guests      /sproutwatch test viewer keys, given up first at the cap
 * @param lastActive  viewer key -> time of their last chat message, for the quiet timeout
 * @param quietMillis how long a present viewer must be quiet before a queued viewer may replace them
 *                    (0 disables the quiet timeout)
 */
public record ReconcileRequest(Map<String, Long> roster, Map<String, Long> pen, List<String> queue,
                               int cap, long graceMillis, long now, boolean persist, Set<String> guests,
                               Map<String, Long> lastActive, long quietMillis) {

    public ReconcileRequest {
        roster = Map.copyOf(Objects.requireNonNull(roster, "roster"));
        pen = Map.copyOf(Objects.requireNonNull(pen, "pen"));
        queue = List.copyOf(Objects.requireNonNull(queue, "queue"));
        guests = Set.copyOf(Objects.requireNonNull(guests, "guests"));
        lastActive = Map.copyOf(Objects.requireNonNull(lastActive, "lastActive"));
    }

    /**
     * Starts a request from the values every tick has. {@link Builder#graceMillis(long)} must also be
     * called; everything else defaults to off: an empty queue, persist off, no guests, and no quiet
     * timeout.
     */
    public static Builder builder(Map<String, Long> roster, Map<String, Long> pen, int cap, long now) {
        return new Builder(roster, pen, cap, now);
    }

    /** Names each optional value; see {@link ReconcileRequest#builder(Map, Map, int, long)}. */
    public static final class Builder {
        private final Map<String, Long> roster;
        private final Map<String, Long> pen;
        private final int cap;
        private final long now;
        private Long graceMillis;
        private List<String> queue = List.of();
        private boolean persist = false;
        private Set<String> guests = Set.of();
        private Map<String, Long> lastActive = Map.of();
        private long quietMillis = 0L;

        private Builder(Map<String, Long> roster, Map<String, Long> pen, int cap, long now) {
            this.roster = roster;
            this.pen = pen;
            this.cap = cap;
            this.now = now;
        }

        /** Required: how long a sprout outlives its viewer when persist is off. */
        public Builder graceMillis(long graceMillis) {
            this.graceMillis = graceMillis;
            return this;
        }

        /** The "!sprout" queue, first in first out. Default: empty. */
        public Builder queue(List<String> queue) {
            this.queue = queue;
            return this;
        }

        /** PersistSprouts. Default: off. */
        public Builder persist(boolean persist) {
            this.persist = persist;
            return this;
        }

        /** /sproutwatch test viewer keys. Default: none. */
        public Builder guests(Set<String> guests) {
            this.guests = guests;
            return this;
        }

        /** Quiet timeout: last chat message per viewer key, and the quiet span (0 disables). Default: off. */
        public Builder quiet(Map<String, Long> lastActive, long quietMillis) {
            this.lastActive = lastActive;
            this.quietMillis = quietMillis;
            return this;
        }

        /** @throws IllegalStateException if {@link #graceMillis(long)} was never called */
        public ReconcileRequest build() {
            if (graceMillis == null) throw new IllegalStateException("ReconcileRequest needs graceMillis");
            return new ReconcileRequest(roster, pen, queue, cap, graceMillis, now, persist, guests,
                lastActive, quietMillis);
        }
    }
}
