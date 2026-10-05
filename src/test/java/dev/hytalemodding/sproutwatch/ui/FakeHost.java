package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.SproutQueue;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The ActionsHost every action test uses. Records every side effect and never touches the engine:
 * World is only a type here.
 */
final class FakeHost implements ActionsHost {
    final SproutwatchConfig config = new SproutwatchConfigAccess().fresh();
    final ChatRoster roster = new ChatRoster(config::ignoredViewers, config::getQueueCommand, new SproutQueue());
    final PenRegistry registry = new PenRegistry();
    final Logger logger = Logger.getLogger("FakeHost");
    boolean running;
    boolean acknowledged;
    String startError;
    int startCalls, stopCalls, saveCalls, restartCalls;
    boolean tickerRunning;
    WorldQueue penQueue = WorldQueue.NOT_LOADED;
    WorldQueue playerQueue = WorldQueue.NOT_LOADED;
    final List<Consumer<World>> penTasks = new ArrayList<>();
    final List<Consumer<World>> playerTasks = new ArrayList<>();

    @Override public SproutwatchConfig config() { return config; }
    @Override public void saveConfig() { saveCalls++; }
    @Override public Logger logger() { return logger; }
    @Override public ChatRoster roster() { return roster; }
    @Override public PenRegistry registry() { return registry; }
    @Override public Set<String> roleSet() { return config.sweepRoles(); }
    /** Per-source states; null = derive them like the plugin does from config + running. */
    Map<String, String> states;
    /** Refuse a start the way the plugin does when no source can start (SproutwatchConfig.nothingToStartReason). */
    boolean refuseLikePlugin;
    /** YouTube is configured but its source fails to build, so it is absent after a start. */
    boolean youTubeFails;
    final java.time.Clock clock = java.time.Clock.fixed(
        java.time.LocalDateTime.of(2026, 10, 3, 12, 0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant(),
        java.time.ZoneId.of("America/Los_Angeles"));
    @Override public Map<String, String> sourceStates() {
        if (states != null) return states;
        boolean twitchReady = config.twitchReady();
        boolean youTubeReady = config.youTubeConfigured();
        if (!running) return StatusSnapshot.sourceStates(null, twitchReady, null, youTubeReady);
        return StatusSnapshot.sourceStates(
            twitchReady ? "connected to #" + config.getTwitchChannel() : null, twitchReady,
            youTubeReady && !youTubeFails ? "connected to YouTube (" + YouTubeStatus.target(config) + ")" : null, youTubeReady);
    }
    @Override public YouTubeStatus youTubeStatus() { return YouTubeStatus.of(config, null, clock); }
    @Override public String listenerState() { return StatusSnapshot.joinStates(sourceStates()); }
    @Override public boolean listenerRunning() { return running; }
    @Override public boolean feedAcknowledged() { return acknowledged; }
    /** Contract: a start always stops the current run first, refused or not. */
    @Override public String startListener() {
        startCalls++;
        running = false;
        String error = startError != null ? startError : refuseLikePlugin ? config.nothingToStartReason() : null;
        if (error != null) return error;
        running = true;
        return null;
    }
    @Override public boolean stopListener() {
        stopCalls++;
        boolean wasRunning = running;
        running = false;
        return wasRunning;
    }
    @Override public boolean tickerRunning() { return tickerRunning; }
    @Override public void restartTicker() { restartCalls++; }
    @Override public World penWorld() { return null; }
    @Override public WorldQueue runOnPenWorld(Consumer<World> task) {
        if (penQueue == WorldQueue.QUEUED) penTasks.add(task);
        return penQueue;
    }
    @Override public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
        if (playerQueue == WorldQueue.QUEUED) playerTasks.add(task);
        return playerQueue;
    }
    @Override public boolean runOnWorld(World world, Runnable task) { return false; }
    int changedCalls;
    @Override public void statusChanged() { changedCalls++; }
    /** Handles looked up, and the futures a test completes to script each outcome. */
    final List<String> lookups = new ArrayList<>();
    final List<java.util.concurrent.CompletableFuture<String>> lookupFutures = new ArrayList<>();
    @Override public java.util.concurrent.CompletableFuture<String> lookUpViewerChannelId(String viewerHandle) {
        lookups.add(viewerHandle);
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        lookupFutures.add(future);
        return future;
    }
}
