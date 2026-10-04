package dev.hytalemodding.sproutwatch.ui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The settings pages currently open, keyed by player UUID. Registered by the page itself when it
 * builds, removed on dismiss (page) and disconnect (plugin). refreshAll() runs after every pen tick
 * on the pen world thread; a page that reports itself gone, or throws, is dropped.
 */
public final class OpenPages {

    /** What the registry needs from a page. */
    public interface Page {
        /** Push the current status to the client. @return false when the page is gone and should be dropped. */
        boolean refreshStatus();
    }

    private final ConcurrentHashMap<UUID, Page> pages = new ConcurrentHashMap<>();
    private final Logger logger;

    public OpenPages(Logger logger) {
        this.logger = logger;
    }

    public void register(UUID playerUuid, Page page) {
        pages.put(playerUuid, page);
    }

    /** Disconnect: whatever page the player had is gone. */
    public void forget(UUID playerUuid) {
        pages.remove(playerUuid);
    }

    /** Dismiss: drop only if this exact page is the registered one (a newer page may have replaced it). */
    public void forget(UUID playerUuid, Page page) {
        pages.remove(playerUuid, page);
    }

    public boolean isOpen(UUID playerUuid) {
        return pages.containsKey(playerUuid);
    }

    public int size() {
        return pages.size();
    }

    public void refreshAll() {
        for (Map.Entry<UUID, Page> e : pages.entrySet()) {
            boolean keep;
            try {
                keep = e.getValue().refreshStatus();
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Sproutwatch settings page refresh failed for " + e.getKey() + "; dropping it", exception);
                keep = false;
            }
            if (!keep) pages.remove(e.getKey(), e.getValue());
        }
    }
}
