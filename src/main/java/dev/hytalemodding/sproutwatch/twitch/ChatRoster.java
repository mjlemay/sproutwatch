package dev.hytalemodding.sproutwatch.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Who is in chat right now: key -> millis when we first saw them since they last left. Keys are lowercase Twitch logins or
 * case-sensitive YouTube keys ({@code yt:<channelId>}).
 * Written from the Twitch and YouTube source threads, read (snapshot) from the pen ticker. The timestamp is
 * only used to order who gets a sprout next; presence is membership in the map.
 *
 * Also feeds the {@link SproutQueue}: a Chat event whose text is the queue command (e.g. "!sprout")
 * queues that viewer; leaving chat (Part) or clear() drops them from it.
 */
public final class ChatRoster {

    private static final String DEFAULT_QUEUE_COMMAND = "!sprout";

    private final ConcurrentHashMap<String, Long> firstSeen = new ConcurrentHashMap<>();
    /** Last chat message per viewer key (join/names set it once); the quiet timeout reads this. */
    private final ConcurrentHashMap<String, Long> lastActive = new ConcurrentHashMap<>();
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
                if (add(chat.viewerKey(), now, skip)) {
                    lastActive.put(chat.viewerKey(), now);
                    if (isQueueCommand(chat.text())) queue.offer(chat.viewerKey());
                }
            }
            case RosterEvent.Part part -> {
                firstSeen.remove(part.login());
                lastActive.remove(part.login());
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

    /** @return true if the viewer key is eligible (not blank, not justinfan*, not ignored); it is then present. */
    private boolean add(String viewerKey, long now, Set<String> skip) {
        if (viewerKey == null || viewerKey.isEmpty()) return false;
        if (viewerKey.startsWith("justinfan")) return false;
        if (skip != null && skip.contains(viewerKey)) return false;
        firstSeen.putIfAbsent(viewerKey, now);
        lastActive.putIfAbsent(viewerKey, now);
        return true;
    }

    /** viewer key -> time of their last chat message (or first sighting). Immutable copy. */
    public Map<String, Long> lastActiveMap() {
        return Map.copyOf(lastActive);
    }

    /** Immutable point-in-time copy. */
    public Map<String, Long> snapshot() {
        return Map.copyOf(firstSeen);
    }

    /**
     * A fake viewer (/sproutwatch test): present like anyone else, but marked so the pen gives it up
     * first at the cap and Clear forgets it. firstSeen is the caller's (0 to jump the spawn order).
     */
    public void addGuest(String viewerKey, long firstSeenAt) {
        if (add(viewerKey, firstSeenAt, ignored.get())) guests.add(viewerKey);
    }

    /** @return true if the viewer key was a guest (it leaves the roster and the queue). */
    public boolean removeGuest(String viewerKey) {
        if (!guests.remove(viewerKey)) return false;
        firstSeen.remove(viewerKey);
        lastActive.remove(viewerKey);
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
        return firstSeen.size();
    }

    public void clear() {
        firstSeen.clear();
        lastActive.clear();
        guests.clear();
        queue.clear();
    }
}
