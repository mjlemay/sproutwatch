package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.prefab.PenTerrain;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Everything SproutwatchActions and its three action classes need from the plugin, narrowed so a
 * unit test can fake it without threads, sockets or engine objects. SproutwatchPlugin implements it.
 */
public interface ActionsHost {

    /** Outcome of asking for work on a world thread. */
    enum WorldQueue { QUEUED, NOT_LOADED, REJECTED }

    SproutwatchConfig config();

    /** Persists the config asynchronously; failures are logged by the plugin. */
    void saveConfig();

    Logger logger();

    ChatRoster roster();

    PenRegistry registry();

    /** NPC roles the pen sweep removes: the configured ones plus the pre-clothing defaults (SproutwatchConfig.sweepRoles). */
    Set<String> roleSet();

    /**
     * "stopped" when no source runs; one source's state alone; with both,
     * "{@code Twitch: <state> · YouTube: <state>}" ({@link StatusSnapshot#joinStates}).
     * Like every status read here it must not take the lock start/stop hold (sources' state hooks call it).
     */
    String listenerState();

    /** True while any chat source runs. */
    boolean listenerRunning();

    /**
     * Insertion-ordered "Twitch" / "YouTube" → state, for sources that are enabled and configured or
     * currently started ({@link StatusSnapshot#sourceStates}).
     */
    Map<String, String> sourceStates();

    /** YouTube settings (key masked) and today's quota usage. */
    YouTubeStatus youTubeStatus();

    /** True when Twitch acknowledged the membership capability on the current connection. */
    boolean feedAcknowledged();

    /**
     * Stops the current run first, always (even when the start is then refused, so a refused
     * restart never leaves a source listening that the new settings turned off), then starts every
     * chat source that can start, and the ticker.
     * @return error text when no source can start (or there is no pen), or null on success
     */
    String startListener();

    /** @return true if a listener was running and has been stopped. */
    boolean stopListener();

    boolean tickerRunning();

    /** Re-arms the ticker with the current TickSeconds. */
    void restartTicker();

    /** The configured pen world, or null when unset or not loaded. */
    World penWorld();

    /** Queues task on the pen world's thread; NOT_LOADED when there is no loaded pen world. */
    WorldQueue runOnPenWorld(Consumer<World> task);

    /** Queues task on the sender's world thread; NOT_LOADED when that world is not loaded. */
    WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task);

    /** world.execute that cannot escape. @return false if the world rejected the task. */
    boolean runOnWorld(World world, Runnable task);

    /**
     * Removes every tracked sprout and any NPC of a sweep role inside bounds (PenClearer.clear).
     * Must run on that world's thread. @return how many entities were removed
     */
    int sweepPen(World world, PenBounds bounds);

    /** The ground under the pen: saved on place, put back on Remove pen and when the pen moves. */
    PenTerrain penTerrain();

    /**
     * Something the page shows changed outside a tick (place/clear finished); the plugin refreshes open
     * settings pages. Invoked from world threads and command threads; implementations must be
     * thread-safe and must not throw.
     */
    void statusChanged();

    /**
     * Resolves a YouTube {@code @handle} to its channel ID off the caller's thread (one API call, 1
     * quota unit, counted against the daily quota) with the configured key. Never blocks the caller.
     * Completes with the channel ID, or exceptionally with a
     * {@link dev.hytalemodding.sproutwatch.youtube.YouTubeException} (its kind says why), a
     * {@link dev.hytalemodding.sproutwatch.youtube.NoYouTubeKey} when no key is configured, or a {@link dev.hytalemodding.sproutwatch.youtube.LookupUnavailable} when the lookup
     * thread has stopped (server shutting down). Completion runs on the lookup thread.
     */
    CompletableFuture<String> lookUpViewerChannelId(String viewerHandle);
}
