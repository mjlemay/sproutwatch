package dev.hytalemodding.sproutwatch.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FIFO of viewer keys (lowercase Twitch logins or case-sensitive {@code yt:<channelId>} keys) who
 * asked for a sprout with the chat queue command (e.g. "!sprout"). No duplicates: a key is queued
 * once until it is consumed (spawned), leaves chat, or the queue is cleared. Written from the
 * Twitch and YouTube source threads, read (snapshot) from the pen ticker, so every
 * method is synchronized.
 */
public final class SproutQueue {

    private final LinkedHashSet<String> viewerKeys = new LinkedHashSet<>();

    /** @return true if the viewer key was appended; false if blank or already queued. */
    public synchronized boolean offer(String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) return false;
        return viewerKeys.add(viewerKey);
    }

    /** @return true if the viewer key was queued and has now been dropped. */
    public synchronized boolean remove(String viewerKey) {
        return viewerKey != null && viewerKeys.remove(viewerKey);
    }

    public synchronized boolean contains(String viewerKey) {
        return viewerKey != null && viewerKeys.contains(viewerKey);
    }

    /** Immutable point-in-time copy in FIFO order. */
    public synchronized List<String> snapshot() {
        return List.copyOf(viewerKeys);
    }

    public synchronized int size() {
        return viewerKeys.size();
    }

    public synchronized void clear() {
        viewerKeys.clear();
    }

    /** Drops every queued viewer key that is not in {@code present} (viewers who left chat). */
    public synchronized void retain(Set<String> present) {
        viewerKeys.retainAll(present == null ? Set.of() : present);
    }
}
