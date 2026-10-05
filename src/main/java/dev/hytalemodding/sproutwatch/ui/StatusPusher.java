package dev.hytalemodding.sproutwatch.ui;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine-free half of the settings page's live status push: the change gate (what was last
 * shown), the dismissed flag, and the handoff to the player's world thread. The page supplies the
 * engine side through two seams: a WorldExecutor over the player's entity store and world, and a
 * PageSink that rebuilds the page or sends a partial update. See SproutwatchSettingsPage.refreshStatus
 * for why the push is change-gated and must run on the player's world thread.
 */
final class StatusPusher {

    /** The player's world thread: whether the caller is on it, and a way to queue work onto it. */
    interface WorldExecutor {
        boolean isInThread();

        void execute(Runnable task);
    }

    /** The page's engine calls. Both run only on the player's world thread. */
    interface PageSink {
        /** Rebuild the whole page; the build records the full state through {@link #recordBuilt}. */
        void rebuild();

        /** Send the state as a partial update. */
        void sendState(PageState state);
    }

    private final PageSink sink;
    private final Logger logger;
    // What the last build() or push showed; null until the first build(). Written by recordBuilt()
    // and pushOnWorldThread() (player's world thread), read by refresh() (any thread); the benign
    // race costs one extra push. Its listsKey changing triggers a rebuild so new rows appear.
    private volatile PageState lastSent;
    // A refresh queued on the pen thread can land after the player dismissed the page.
    private volatile boolean dismissed;

    StatusPusher(PageSink sink, Logger logger) {
        this.sink = sink;
        this.logger = logger;
    }

    /** build() showed this state. */
    void recordBuilt(PageState state) {
        lastSent = state;
    }

    void dismiss() {
        dismissed = true;
    }

    boolean isDismissed() {
        return dismissed;
    }

    /**
     * Called from any thread. Computes the current state and, only if it differs from what was last
     * shown, hands the push to the player's world thread (directly when already on it).
     *
     * @return false when the state could not be computed or handed off (for example the world is
     *         unloading), so the page should be dropped; true otherwise
     */
    boolean refresh(Supplier<PageState> current, WorldExecutor world) {
        try {
            PageState state = current.get();
            if (state.equals(lastSent)) return true;
            Runnable push = () -> pushOnWorldThread(state);
            if (world.isInThread()) push.run();
            else world.execute(push);
            return true;
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page refresh failed; dropping the page", exception);
            return false;
        }
    }

    /** Runs on the player's world thread. A failure here is logged; the page stays registered. */
    private void pushOnWorldThread(PageState state) {
        if (dismissed) return;
        try {
            PageState shown = lastSent;
            if (shown == null || !state.listsKey().equals(shown.listsKey())) {
                sink.rebuild();   // build() records the full state
                return;
            }
            sink.sendState(state);
            lastSent = state;
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page push failed", exception);
        }
    }
}
