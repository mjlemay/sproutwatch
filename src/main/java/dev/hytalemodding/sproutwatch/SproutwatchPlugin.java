package dev.hytalemodding.sproutwatch;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.Config;
import dev.hytalemodding.sproutwatch.camera.ChairCameraService;
import dev.hytalemodding.sproutwatch.camera.SeatedInvulnerabilitySystem;
import dev.hytalemodding.sproutwatch.camera.SeatedPlayers;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.SproutQueue;
import dev.hytalemodding.sproutwatch.commands.SproutwatchCommand;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenDespawnSystem;
import dev.hytalemodding.sproutwatch.pen.PenGuardSystem;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.pen.PenTicker;
import dev.hytalemodding.sproutwatch.pen.SproutDeathSystem;
import dev.hytalemodding.sproutwatch.pen.SproutSpawner;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;
import dev.hytalemodding.sproutwatch.ui.ActionsHost;
import dev.hytalemodding.sproutwatch.ui.OpenPages;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActions;
import dev.hytalemodding.sproutwatch.ui.StatusSnapshot;
import dev.hytalemodding.sproutwatch.ui.YouTubeStatus;
import dev.hytalemodding.sproutwatch.youtube.QuotaStore;
import dev.hytalemodding.sproutwatch.youtube.YouTubeChatSource;
import dev.hytalemodding.sproutwatch.youtube.YouTubeService;

import javax.annotation.Nonnull;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class SproutwatchPlugin extends JavaPlugin implements ActionsHost {

    private final Config<SproutwatchConfig> config;
    private final Logger bridgeLogger;
    private java.util.logging.Handler bridgeHandler;
    private final ChatRoster roster;
    private final DisplayNames displayNames;
    private final PenRegistry registry;
    private final SproutSpawner spawner;
    private final PenTicker ticker;
    private final SeatedPlayers seatedPlayers = new SeatedPlayers();
    private final ChairCameraService cameraService;
    private final SproutwatchActions actions;
    private final OpenPages openPages;
    /**
     * The chat sources started by the last Start (empty when stopped). Replaced as a whole under the
     * plugin monitor; read without it (LOCKING RULE: state hooks and roster suppliers run on source
     * threads while stopListener joins them, so status reads must never take the monitor).
     */
    private volatile List<ChatSource> sources = List.of();
    /** The shared YouTube client, the running pacer and the @handle lookups (its own lock, never the plugin monitor). */
    private final YouTubeService youTubeService;
    private final AtomicBoolean bootSweepDone = new AtomicBoolean();

    public SproutwatchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = withConfig("Sproutwatch_config", SproutwatchConfig.CODEC);
        this.bridgeLogger = createBridgeLogger();
        // Ignore list and queue command are read live from the config on every roster event, so
        // /sproutwatch ignore add|remove and a QueueCommand edit take effect without a restart.
        this.roster = new ChatRoster(() -> config.get().ignoredViewers(), () -> config.get().getQueueCommand(), new SproutQueue());
        this.displayNames = new DisplayNames();
        this.registry = new PenRegistry();
        this.spawner = new SproutSpawner(config::get, registry, displayNames, bridgeLogger);
        this.ticker = new PenTicker(config::get, roster, registry, spawner, bridgeLogger);
        ticker.setOnWorldReady(this::bootSweep);
        this.cameraService = new ChairCameraService(config::get, bridgeLogger, seatedPlayers);
        this.actions = new SproutwatchActions(this);
        this.openPages = new OpenPages(bridgeLogger);
        this.youTubeService = new YouTubeService(config::get, new QuotaStore(config::get, this::saveConfig, System::currentTimeMillis),
            bridgeLogger);
        // Live status for open settings pages: after every tick, on the pen world thread.
        ticker.setAfterTick(openPages::refreshAll);
    }

    @Override
    protected void setup() {
        if (config.get().upgradeLegacyRoles()) {
            bridgeLogger.info("Sproutwatch: Roles upgraded to the clothed Kweebec roles (Sprout_Sproutling, Sprout_Sapling_*)");
        }
        config.save().whenComplete((value, throwable) -> {
            if (throwable != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", throwable);
        });
        getCommandRegistry().registerCommand(new SproutwatchCommand(this));
        // Disconnect never fires the mount-removed callback; drop the player's camera state here.
        try {
            getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
                java.util.UUID uuid = event.getPlayerRef().getUuid();
                cameraService.forget(uuid);
                openPages.forget(uuid);
            });
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch disconnect listener failed to register");
        }
        // ECS systems via getEntityStoreRegistry(), the route verified on 0.6.3. Each
        // registration is guarded on its own so one failure never drops the other.
        try {
            getEntityStoreRegistry().registerSystem(new PenDespawnSystem(registry, bridgeLogger));
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch despawn system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new SproutDeathSystem(registry, bridgeLogger));
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch death system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(cameraService);
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch chair camera failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new SeatedInvulnerabilitySystem(seatedPlayers));
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch seated invulnerability failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new PenGuardSystem(config::get, registry, bridgeLogger));
        } catch (RuntimeException exception) {
            getLogger().atSevere().withCause(exception).log("Sproutwatch pen guard failed to register");
        }
        // Restart safety: sweep leftover younglings out of the pen if its world is already loaded.
        World world = PenTicker.resolveWorld(config.get());
        if (world != null) runOnWorld(world, () -> bootSweep(world));
        if (config.get().isAutoStartOnBoot()) {
            String error = startListener();
            if (error != null) getLogger().atWarning().log("%s", error);
        }
    }

    @Override
    protected void shutdown() {
        // Lookups first: queued ones are failed (LookupUnavailable), an in-flight one is interrupted.
        youTubeService.shutdownLookups();
        stopListener();   // flushes the quota usage into a config save
        youTubeService.close();   // closes the client, then waits up to 1 s for the lookup thread
        try {
            config.save().get(2, java.util.concurrent.TimeUnit.SECONDS);   // let the last usage reach disk before exit
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            bridgeLogger.warning("Sproutwatch: final config save did not finish: " + exception.getClass().getSimpleName());
        }
        ticker.shutdown();
        if (bridgeHandler != null) bridgeLogger.removeHandler(bridgeHandler);
    }

    public Config<SproutwatchConfig> getConfigHolder() { return config; }
    public Logger getBridgeLogger() { return bridgeLogger; }
    public ChatRoster getRoster() { return roster; }
    /** Roster key -> nameplate name (YouTube display names); cleared with the roster on Stop. */
    public DisplayNames getDisplayNames() { return displayNames; }
    /** The "!sprout" priority queue the roster feeds (FIFO of viewer keys who asked for a sprout). */
    public SproutQueue getQueue() { return roster.queue(); }
    public PenRegistry getRegistry() { return registry; }
    public PenTicker getTicker() { return ticker; }
    public ChairCameraService getCameraService() { return cameraService; }
    /** The started Twitch client, or null. */
    public TwitchMembershipClient getClient() { return twitch(); }
    /** Shared mutation service used by the commands and the settings page. */
    public SproutwatchActions getActions() { return actions; }
    /** Settings pages currently open, for live status pushes and disconnect cleanup. */
    public OpenPages getOpenPages() { return openPages; }

    // ---- ActionsHost -------------------------------------------------------------------------

    @Override public SproutwatchConfig config() { return config.get(); }
    @Override public Logger logger() { return bridgeLogger; }
    @Override public ChatRoster roster() { return roster; }
    @Override public PenRegistry registry() { return registry; }

    @Override
    public Set<String> roleSet() {
        return config.get().sweepRoles();
    }

    @Override
    public void saveConfig() {
        config.save().whenComplete((value, throwable) -> {
            if (throwable != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", throwable);
        });
    }

    @Override
    public String listenerState() {
        return StatusSnapshot.joinStates(sourceStates());
    }

    @Override
    public boolean listenerRunning() {
        for (ChatSource s : sources) if (s.isRunning()) return true;
        return false;
    }

    @Override
    public boolean feedAcknowledged() {
        TwitchMembershipClient client = twitch();
        return client != null && client.isMembershipAcknowledged();
    }

    @Override
    public Map<String, String> sourceStates() {
        SproutwatchConfig currentConfig = config.get();
        String twitchState = null, youTubeState = null;
        for (ChatSource s : sources) {
            if (s instanceof TwitchMembershipClient) twitchState = s.getState();
            else if (s instanceof YouTubeChatSource) youTubeState = s.getState();
        }
        return StatusSnapshot.sourceStates(twitchState, currentConfig.twitchReady(), youTubeState, currentConfig.youTubeConfigured());
    }

    @Override
    public YouTubeStatus youTubeStatus() {
        return YouTubeStatus.of(config.get(), youTubeService.currentPacer(), Clock.systemDefaultZone());
    }

    /**
     * @return error text, or null on success. Stops the current run first, always (ActionsHost
     * contract): a refused restart must not leave a source listening. Synchronized: commands may race.
     */
    @Override
    public synchronized String startListener() {
        stopListener();
        SproutwatchConfig currentConfig = config.get();
        String nothing = currentConfig.nothingToStartReason();
        if (nothing != null) return nothing;
        if (!currentConfig.isPenSet()) {
            return "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        }
        List<ChatSource> next = new ArrayList<>();
        if (currentConfig.twitchReady()) {
            next.add(new TwitchMembershipClient(currentConfig.getTwitchChannel(), roster, bridgeLogger)); // one-shot per start
        }
        if (currentConfig.youTubeConfigured()) {
            ChatSource youTube = youTubeService.newSource(currentConfig, roster, displayNames);
            if (youTube != null) next.add(youTube);
        }
        List<ChatSource> started = new ArrayList<>();
        for (ChatSource s : next) {
            try {
                s.setOnStateChange(this::statusChanged); // Connecting... -> Stop flips on open pages at once
                s.start();
                started.add(s);
            } catch (RuntimeException exception) {
                // Class name only: a message could in theory echo configuration.
                bridgeLogger.warning("Sproutwatch: a chat source failed to start: " + exception.getClass().getSimpleName());
                if (s instanceof YouTubeChatSource) youTubeService.sourceFailedToStart();
            }
        }
        if (started.isEmpty()) return "No chat source could start (see the server log).";
        sources = List.copyOf(started);
        World world = PenTicker.resolveWorld(currentConfig);
        if (world != null) runOnWorld(world, () -> bootSweep(world));
        ticker.start();
        return null;
    }

    /** @return true if a listener was running and has been stopped. Sprouts stay until /sproutwatch clear. */
    @Override
    public synchronized boolean stopListener() {
        List<ChatSource> old = sources;
        sources = List.of();
        for (ChatSource s : old) s.stop();   // stop the producers (each joins <= 1 s) before clearing what they produce
        ticker.stop();
        roster.clear();
        displayNames.clear();   // names belong to the session's roster
        youTubeService.stopped();   // saves the quota usage; closes the client once YouTube is off
        // True when a Start was in effect, even if every source has since ended on its own (chat
        // ended): the ticker was still running, so Stop did stop something.
        return !old.isEmpty();
    }

    /** Resolves an allow/ignore @handle on the YouTube lookup thread (see {@link YouTubeService}). */
    @Override
    public java.util.concurrent.CompletableFuture<String> lookUpViewerChannelId(String viewerHandle) {
        return youTubeService.lookUpViewerChannelId(viewerHandle);
    }

    /** The started Twitch client, or null. Lock-free (volatile read). */
    private TwitchMembershipClient twitch() {
        for (ChatSource s : sources) if (s instanceof TwitchMembershipClient client) return client;
        return null;
    }

    @Override public boolean tickerRunning() { return ticker.isRunning(); }
    @Override public void restartTicker() { ticker.start(); }
    /** Place/clear finished outside a tick: push the new status to every open settings page. */
    @Override public void statusChanged() { openPages.refreshAll(); }

    @Override
    public World penWorld() {
        return PenTicker.resolveWorld(config.get());
    }

    @Override
    public WorldQueue runOnPenWorld(Consumer<World> task) {
        World world = PenTicker.resolveWorld(config.get());
        if (world == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(world, () -> task.accept(world)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    @Override
    public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
        Universe universe = Universe.get();
        java.util.UUID worldUuid = sender.getWorldUuid(); // nullable mid-transfer
        World world = (universe == null || worldUuid == null) ? null : universe.getWorld(worldUuid);
        if (world == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(world, () -> task.accept(world)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    /** world.execute that cannot escape: a world mid-unload rejects tasks. @return false if rejected. */
    @Override
    public boolean runOnWorld(World world, Runnable task) {
        try { world.execute(task); return true; }
        catch (RuntimeException exception) { bridgeLogger.log(Level.WARNING, "Sproutwatch: world rejected task (unloading?)", exception); return false; }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** Restart safety: once per boot, sweep leftover younglings out of the pen. World thread. */
    private void bootSweep(World world) {
        if (!bootSweepDone.compareAndSet(false, true)) return;
        PenClearer.clear(world, registry, roleSet(), PenBounds.fromConfig(config.get()), bridgeLogger);
    }

    /**
     * Bridges java.util.logging (used by every component) onto this plugin's HytaleLogger, a
     * Flogger with only the fluent at(Level) API. Verified on 0.6.3.
     */
    private Logger createBridgeLogger() {
        Logger jul = Logger.getLogger("Sproutwatch");
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        // On 0.6.3 the server's HytaleLogManager returns a HytaleJdkLogger that forwards records straight
        // to the engine backend (prefix [Sproutwatch]); this handler only matters on a plain JVM (unit tests).
        if (jul.getHandlers().length == 0) {
            SimpleFormatter formatter = new SimpleFormatter();
            bridgeHandler = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord r) {
                    String message = formatter.formatMessage(r);
                    var api = getLogger().at(r.getLevel());
                    if (r.getThrown() != null) api = api.withCause(r.getThrown());
                    api.log("%s", message);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            jul.addHandler(bridgeHandler);
        }
        return jul;
    }
}
