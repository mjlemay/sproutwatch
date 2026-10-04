package dev.hytalemodding.sproutwatch.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Who is in chat right now: login -> millis when we first saw them since they last left.
 * Written from the Twitch client thread, read (snapshot) from the pen ticker. The timestamp is
 * only used to order who gets a sprout next; presence is membership in the map.
 *
 * Also feeds the {@link SproutQueue}: a Chat event whose text is the queue command (e.g. "!sprout")
 * queues that viewer; leaving chat (Part) or clear() drops them from it.
 */
public final class ChatRoster {

    private static final String DEFAULT_QUEUE_COMMAND = "!sprout";

    private final ConcurrentHashMap<String, Long> firstSeen = new ConcurrentHashMap<>();
    /** Last chat message per login (join/names set it once); the quiet timeout reads this. */
    private final ConcurrentHashMap<String, Long> lastActive = new ConcurrentHashMap<>();
    /** Logins added by /sproutwatch test: present until forgotten (Twitch never PARTs them). */
    private final Set<String> guests = ConcurrentHashMap.newKeySet();
    private final Supplier<Set<String>> ignored;
    private final Supplier<String> queueCommand;
    private final SproutQueue queue;

    /**
     * @param ignored      lowercase logins that never enter the roster (read on every apply, so config edits take effect live)
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
            case RosterEvent.Names n -> { for (String l : n.logins()) add(l, now, skip); }
            case RosterEvent.Join j -> add(j.login(), now, skip);
            case RosterEvent.Chat c -> {
                if (add(c.login(), now, skip)) {
                    lastActive.put(c.login(), now);
                    if (isQueueCommand(c.text())) queue.offer(c.login());
                }
            }
            case RosterEvent.Part p -> {
                firstSeen.remove(p.login());
                lastActive.remove(p.login());
                guests.remove(p.login());
                queue.remove(p.login());
            }
        }
    }

    private boolean isQueueCommand(String text) {
        if (text == null) return false;
        String cmd = queueCommand.get();
        if (cmd == null) cmd = DEFAULT_QUEUE_COMMAND;
        cmd = cmd.trim();
        return !cmd.isEmpty() && text.trim().equalsIgnoreCase(cmd);
    }

    /** @return true if the login is eligible (not blank, not justinfan*, not ignored); it is then present. */
    private boolean add(String login, long now, Set<String> skip) {
        if (login == null || login.isEmpty()) return false;
        if (login.startsWith("justinfan")) return false;
        if (skip != null && skip.contains(login)) return false;
        firstSeen.putIfAbsent(login, now);
        lastActive.putIfAbsent(login, now);
        return true;
    }

    /** login -> time of their last chat message (or first sighting). Immutable copy. */
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
    public void addGuest(String login, long firstSeenAt) {
        if (add(login, firstSeenAt, ignored.get())) guests.add(login);
    }

    /** @return true if the login was a guest (it leaves the roster and the queue). */
    public boolean removeGuest(String login) {
        if (!guests.remove(login)) return false;
        firstSeen.remove(login);
        lastActive.remove(login);
        queue.remove(login);
        return true;
    }

    public Set<String> guests() {
        return Set.copyOf(guests);
    }

    /** Drops every guest from the roster (pen Clear). @return the forgotten logins, sorted. */
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
