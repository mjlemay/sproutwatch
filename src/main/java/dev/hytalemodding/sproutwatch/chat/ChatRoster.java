package dev.hytalemodding.sproutwatch.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Who is in chat right now. Keys are lowercase Twitch logins or case-sensitive YouTube keys
 * ({@code yt:<channelId>}). Each present viewer has one {@link Presence} holding two timestamps:
 * - firstSeen: millis when we first saw them since they last left; it orders who gets a sprout next.
 * - lastActive: millis of their last chat message (a join or names sighting sets it once); the quiet
 *   timeout reads it.
 * Presence is membership in the map. Both timestamps live in one entry and every update replaces
 * the entry atomically, so the two never disagree about who is present.
 *
 * Written from the Twitch and YouTube source threads, read ({@link #snapshot()},
 * {@link #lastActiveMap()}) from the pen ticker.
 *
 * Also feeds the {@link SproutQueue}: a Chat event whose text is the queue command (e.g. "!sprout")
 * queues that viewer; leaving chat (Part) or clear() drops them from it.
 */
public final class ChatRoster {

    private static final String DEFAULT_QUEUE_COMMAND = "!sprout";

    /** One present viewer's timestamps, replaced as a whole on every update. */
    private record Presence(long firstSeen, long lastActive) {}

    private final ConcurrentHashMap<String, Presence> presence = new ConcurrentHashMap<>();
    /** Viewer keys added by /sproutwatch test: present until forgotten (Twitch never PARTs them). */
    private final Set<String> guests = ConcurrentHashMap.newKeySet();
    private final Supplier<Set<String>> ignored;
    private final Supplier<String> queueCommand;
    private final SproutQueue queue;

    /**
     * @param ignored      keys (lowercase Twitch logins, case-sensitive YouTube keys) that never enter the roster (read on every apply, so config edits take effect live)
     * @param queueCommand chat text that queues a viewer, compared trimmed and case-insensitively (read on every apply)
     * @param queue        the FIFO this roster feeds
     */
    public ChatRoster(Supplier<Set<String>> ignored, Supplier<String> queueCommand, SproutQueue queue) {
        this.ignored = ignored;
        this.queueCommand = queueCommand;
        this.queue = queue;
    }

    /** Convenience: default "!sprout" command and a fresh queue. */
    public ChatRoster(Supplier<Set<String>> ignored) {
        this(ignored, () -> DEFAULT_QUEUE_COMMAND, new SproutQueue());
    }

    public SproutQueue queue() {
        return queue;
    }

    public void apply(RosterEvent event, long now) {
        if (event == null) return;
        Set<String> skip = ignored.get();
        switch (event) {
            case RosterEvent.Names names -> { for (String login : names.logins()) add(login, now, skip); }
            case RosterEvent.Join join -> add(join.login(), now, skip);
            case RosterEvent.Chat chat -> {
                if (isEligible(chat.viewerKey(), skip)) {
                    // A first sighting sets both timestamps; otherwise keep firstSeen and move lastActive.
                    presence.merge(chat.viewerKey(), new Presence(now, now),
                        (current, sighting) -> new Presence(current.firstSeen(), now));
                    if (isQueueCommand(chat.text())) queue.offer(chat.viewerKey());
                }
            }
            case RosterEvent.Part part -> {
                presence.remove(part.login());
                guests.remove(part.login());
                queue.remove(part.login());
            }
        }
    }

    private boolean isQueueCommand(String text) {
        if (text == null) return false;
        String command = queueCommand.get();
        if (command == null) command = DEFAULT_QUEUE_COMMAND;
        command = command.trim();
        return !command.isEmpty() && text.trim().equalsIgnoreCase(command);
    }

    /** @return true if the viewer key may enter the roster (not blank, not justinfan*, not ignored). */
    private static boolean isEligible(String viewerKey, Set<String> skip) {
        if (viewerKey == null || viewerKey.isEmpty()) return false;
        if (viewerKey.startsWith("justinfan")) return false;
        return skip == null || !skip.contains(viewerKey);
    }

    /**
     * A sighting without a message: a new viewer gets both timestamps set to {@code now}; an existing
     * one keeps both.
     *
     * @return true if the viewer key is eligible; it is then present.
     */
    private boolean add(String viewerKey, long now, Set<String> skip) {
        if (!isEligible(viewerKey, skip)) return false;
        presence.putIfAbsent(viewerKey, new Presence(now, now));
        return true;
    }

    /** viewer key -> time of their last chat message (or first sighting). Immutable copy. */
    public Map<String, Long> lastActiveMap() {
        return copyTimestamps(Presence::lastActive);
    }

    /** viewer key -> time first seen since they last left. Immutable point-in-time copy. */
    public Map<String, Long> snapshot() {
        return copyTimestamps(Presence::firstSeen);
    }

    /** One pass over the presence map, keeping one timestamp per viewer key. Immutable copy. */
    private Map<String, Long> copyTimestamps(ToLongFunction<Presence> timestamp) {
        Map<String, Long> copy = new HashMap<>();
        presence.forEach((viewerKey, viewerPresence) -> copy.put(viewerKey, timestamp.applyAsLong(viewerPresence)));
        return Map.copyOf(copy);
    }

    /**
     * A fake viewer (/sproutwatch test): present like anyone else, but marked so the pen gives it up
     * first at the cap and Clear forgets it. A new guest's firstSeen and lastActive are both the
     * caller's {@code firstSeenAt} (0 to jump the spawn order).
     */
    public void addGuest(String viewerKey, long firstSeenAt) {
        if (add(viewerKey, firstSeenAt, ignored.get())) guests.add(viewerKey);
    }

    /** @return true if the viewer key was a guest (it leaves the roster and the queue). */
    public boolean removeGuest(String viewerKey) {
        if (!guests.remove(viewerKey)) return false;
        presence.remove(viewerKey);
        queue.remove(viewerKey);
        return true;
    }

    public Set<String> guests() {
        return Set.copyOf(guests);
    }

    /** Drops every guest from the roster (pen Clear). @return the forgotten viewer keys, sorted. */
    public List<String> forgetGuests() {
        List<String> gone = new ArrayList<>(guests);
        gone.sort(null);
        for (String g : gone) removeGuest(g);
        return gone;
    }

    public int size() {
        return presence.size();
    }

    public void clear() {
        presence.clear();
        guests.clear();
        queue.clear();
    }
}
