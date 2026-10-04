package dev.hytalemodding.sproutwatch.twitch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FIFO of lowercase logins who asked for a sprout with the chat queue command (e.g. "!sprout").
 * No duplicates: a login is queued once until it is consumed (spawned), leaves chat, or the queue
 * is cleared. Written from the Twitch client thread, read (snapshot) from the pen ticker, so every
 * method is synchronized.
 */
public final class SproutQueue {

    private final LinkedHashSet<String> logins = new LinkedHashSet<>();

    /** @return true if the login was appended; false if blank or already queued. */
    public synchronized boolean offer(String login) {
        if (login == null || login.isBlank()) return false;
        return logins.add(login);
    }

    /** @return true if the login was queued and has now been dropped. */
    public synchronized boolean remove(String login) {
        return login != null && logins.remove(login);
    }

    public synchronized boolean contains(String login) {
        return login != null && logins.contains(login);
    }

    /** Immutable point-in-time copy in FIFO order. */
    public synchronized List<String> snapshot() {
        return List.copyOf(logins);
    }

    public synchronized int size() {
        return logins.size();
    }

    public synchronized void clear() {
        logins.clear();
    }

    /** Drops every queued login that is not in {@code present} (viewers who left chat). */
    public synchronized void retain(Set<String> present) {
        logins.retainAll(present == null ? Set.of() : present);
    }
}
