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
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
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
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.SproutQueue;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;
import dev.hytalemodding.sproutwatch.ui.ActionsHost;
import dev.hytalemodding.sproutwatch.ui.OpenPages;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActions;
import dev.hytalemodding.sproutwatch.ui.StatusSnapshot;
import dev.hytalemodding.sproutwatch.ui.YouTubeStatus;
import dev.hytalemodding.sproutwatch.youtube.QuotaPacer;
import dev.hytalemodding.sproutwatch.youtube.QuotaStore;
import dev.hytalemodding.sproutwatch.youtube.YouTubeApi;
import dev.hytalemodding.sproutwatch.youtube.YouTubeChatSource;

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
    private final ChairCameraService cameraService;
    private final SproutwatchActions actions;
    private final OpenPages openPages;
    /**
     * The chat sources started by the last Start (empty when stopped). Replaced as a whole under the
     * plugin monitor; read without it (LOCKING RULE: state hooks and roster suppliers run on source
     * threads while stopListener joins them, so status reads must never take the monitor).
     */
    private volatile List<ChatSource> sources = List.of();
    /** The running YouTube source's pacer (null when YouTube is not started); read by youTubeStatus. */
    private volatile QuotaPacer ytPacer;
    /** One shared client per plugin, recreated when the key changes. Guarded by the plugin monitor. */
    private YouTubeApi ytApi;
    private String ytApiKey;
    private final QuotaStore quotaStore;
    /** Allow/ignore @handle lookups: one daemon thread, so a lookup never blocks a world or command thread. */
    private final java.util.concurrent.ExecutorService ytLookups = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sproutwatch-YouTube-lookup");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean bootSweepDone = new AtomicBoolean();

    public SproutwatchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = withConfig("Sproutwatch_config", SproutwatchConfig.CODEC);
        this.bridgeLogger = createBridgeLogger();
        // Ignore list and queue command are read live from the config on every roster event, so
        // /sproutwatch ignore add|remove and a QueueCommand edit take effect without a restart.
        this.roster = new ChatRoster(() -> config.get().ignoredLogins(), () -> config.get().getQueueCommand(), new SproutQueue());
        this.displayNames = new DisplayNames();
        this.registry = new PenRegistry();
        this.spawner = new SproutSpawner(config::get, registry, displayNames, bridgeLogger);
        this.ticker = new PenTicker(config::get, roster, registry, spawner, bridgeLogger);
        ticker.setOnWorldReady(this::bootSweep);
        this.cameraService = new ChairCameraService(config::get, bridgeLogger);
        this.actions = new SproutwatchActions(this);
        this.openPages = new OpenPages(bridgeLogger);
        this.quotaStore = new QuotaStore(config::get, this::saveConfig, System::currentTimeMillis);
        // Live status for open settings pages: after every tick, on the pen world thread.
        ticker.setAfterTick(openPages::refreshAll);
    }

    @Override
    protected void setup() {
        if (config.get().upgradeLegacyRoles()) {
            bridgeLogger.info("Sproutwatch: Roles upgraded to the clothed Kweebec roles (Sprout_Sproutling, Sprout_Sapling_*)");
        }
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
        });
        getCommandRegistry().registerCommand(new SproutwatchCommand(this));
        // Disconnect never fires the mount-removed callback; drop the player's camera state here.
        try {
            getEventRegistry().register(PlayerDisconnectEvent.class, e -> {
                java.util.UUID uuid = e.getPlayerRef().getUuid();
                cameraService.forget(uuid);
                openPages.forget(uuid);
            });
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch disconnect listener failed to register");
        }
        // ECS systems via getEntityStoreRegistry(), the route Subinator proved on 0.6.3. Each
        // registration is guarded on its own so one failure never drops the other.
        try {
            getEntityStoreRegistry().registerSystem(new PenDespawnSystem(registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch despawn system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new SproutDeathSystem(registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch death system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(cameraService);
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch chair camera failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new SeatedInvulnerabilitySystem(cameraService));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch seated invulnerability failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new PenGuardSystem(config::get, registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch pen guard failed to register");
        }
        // Restart safety: sweep leftover younglings out of the pen if its world is already loaded.
        World w = PenTicker.resolveWorld(config.get());
        if (w != null) runOnWorld(w, () -> bootSweep(w));
        if (config.get().isAutoStartOnBoot()) {
            String err = startListener();
            if (err != null) getLogger().atWarning().log("%s", err);
        }
    }

    @Override
    protected void shutdown() {
        // Lookups first: queued ones are failed (LookupUnavailable), an in-flight one is interrupted.
        for (Runnable r : ytLookups.shutdownNow()) {
            if (r instanceof LookupTask t) t.future.completeExceptionally(new ActionsHost.LookupUnavailable());
        }
        stopListener();   // flushes the quota usage into a config save
        closeYouTubeApi();
        try {
            ytLookups.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);   // its quota unit may still land
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            config.save().get(2, java.util.concurrent.TimeUnit.SECONDS);   // let the last usage reach disk before exit
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            bridgeLogger.warning("Sproutwatch: final config save did not finish: " + e.getClass().getSimpleName());
        }
        ticker.shutdown();
        if (bridgeHandler != null) bridgeLogger.removeHandler(bridgeHandler);
    }

    public Config<SproutwatchConfig> getConfigHolder() { return config; }
    public Logger getBridgeLogger() { return bridgeLogger; }
    public ChatRoster getRoster() { return roster; }
    /** Roster key -> nameplate name (YouTube display names); cleared with the roster on Stop. */
    public DisplayNames getDisplayNames() { return displayNames; }
    /** The "!sprout" priority queue the roster feeds (FIFO of logins who asked for a sprout). */
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
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
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
    public boolean feedAcked() {
        TwitchMembershipClient c = twitch();
        return c != null && c.isMembershipAcked();
    }

    @Override
    public Map<String, String> sourceStates() {
        SproutwatchConfig cfg = config.get();
        String tw = null, yt = null;
        for (ChatSource s : sources) {
            if (s instanceof TwitchMembershipClient) tw = s.getState();
            else if (s instanceof YouTubeChatSource) yt = s.getState();
        }
        return StatusSnapshot.sourceStates(tw, cfg.twitchReady(), yt, cfg.youTubeConfigured());
    }

    @Override
    public YouTubeStatus youTubeStatus() {
        return YouTubeStatus.of(config.get(), ytPacer, Clock.systemDefaultZone());
    }

    /**
     * @return error text, or null on success. Stops the current run first, always (ActionsHost
     * contract): a refused restart must not leave a source listening. Synchronized: commands may race.
     */
    @Override
    public synchronized String startListener() {
        stopListener();
        SproutwatchConfig cfg = config.get();
        String nothing = cfg.nothingToStartReason();
        if (nothing != null) return nothing;
        if (!cfg.isPenSet()) {
            return "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        }
        List<ChatSource> next = new ArrayList<>();
        if (cfg.twitchReady()) {
            next.add(new TwitchMembershipClient(cfg.getTwitchChannel(), roster, bridgeLogger)); // one-shot per start
        }
        if (cfg.youTubeConfigured()) {
            ChatSource yt = newYouTubeSource(cfg);
            if (yt != null) next.add(yt);
        }
        List<ChatSource> started = new ArrayList<>();
        for (ChatSource s : next) {
            try {
                s.setOnStateChange(this::statusChanged); // Connecting... -> Stop flips on open pages at once
                s.start();
                started.add(s);
            } catch (RuntimeException e) {
                // Class name only: a message could in theory echo configuration.
                bridgeLogger.warning("Sproutwatch: a chat source failed to start: " + e.getClass().getSimpleName());
                if (s instanceof YouTubeChatSource) ytPacer = null;
            }
        }
        if (started.isEmpty()) return "No chat source could start (see the server log).";
        sources = List.copyOf(started);
        World w = PenTicker.resolveWorld(cfg);
        if (w != null) runOnWorld(w, () -> bootSweep(w));
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
        QuotaPacer p = ytPacer;
        ytPacer = null;
        if (p != null) p.setUsageListener(null);   // a call still in flight after the join may not save
        quotaStore.flush(p);
        if (!config.get().youTubeConfigured()) closeYouTubeApi();   // no idle HttpClient once YouTube is off
        // True when a Start was in effect, even if every source has since ended on its own (chat
        // ended): the ticker was still running, so Stop did stop something.
        return !old.isEmpty();
    }

    /** Fresh pacer (restored from the saved usage) and source on the shared API client; null if it cannot be built. */
    private ChatSource newYouTubeSource(SproutwatchConfig cfg) {
        try {
            String key = cfg.getYouTubeApiKey();
            if (ytApi == null || !key.equals(ytApiKey)) {
                closeYouTubeApi();
                ytApi = new YouTubeApi(key);
                ytApiKey = key;
            }
            QuotaPacer pacer = new QuotaPacer(cfg.getYouTubeStreamHours(), Clock.systemDefaultZone());
            quotaStore.attach(pacer);
            ChatSource source = new YouTubeChatSource(cfg.getYouTubeHandle(), cfg.getYouTubeVideo(), ytApi, pacer,
                roster, displayNames, bridgeLogger);
            ytPacer = pacer;
            return source;
        } catch (RuntimeException e) {
            // Class name only: a message could in theory echo configuration.
            bridgeLogger.warning("Sproutwatch: YouTube source could not start: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Detaches the shared client under the monitor and closes it on a virtual thread:
     * HttpClient.close() waits for in-flight requests (a lookup or poll, up to the call timeout), and
     * that wait must never hold the plugin monitor or block a world or command thread.
     */
    private void closeYouTubeApi() {
        YouTubeApi api;
        synchronized (this) {
            api = ytApi;
            ytApi = null;
            ytApiKey = null;
        }
        if (api != null) Thread.startVirtualThread(api::close);
    }

    /**
     * Resolves an allow/ignore @handle on the lookup thread with the shared client (created for the
     * configured key when the listener has not made one). The plugin monitor is only taken on the
     * lookup thread, never the caller's. Each call that reaches the API is counted as 1 quota unit.
     * Failures carry the YtException kind only, never the key.
     */
    @Override
    public java.util.concurrent.CompletableFuture<String> lookUpYouTubeChannel(String handle) {
        LookupTask task = new LookupTask(handle);
        try {
            ytLookups.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            task.future.completeExceptionally(new ActionsHost.LookupUnavailable());
        }
        return task.future;
    }

    /** One queued lookup; shutdown() fails the future of any task it never ran. */
    private final class LookupTask implements Runnable {
        final String handle;
        final java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();

        LookupTask(String handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            try {
                YouTubeApi api = lookupApi();
                if (api == null) throw new ActionsHost.NoYouTubeKey();
                try {
                    future.complete(api.channelIdForHandle(handle));
                } finally {
                    countLookupUnit();
                    releaseLookupApi(api);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }

    /** 1 unit: into the running pacer (which persists it), else straight into the saved usage. */
    private void countLookupUnit() {
        QuotaPacer p = ytPacer;
        if (p != null) p.recordCall(1);
        else quotaStore.addUnits(1, java.time.LocalDate.now(QuotaPacer.QUOTA_ZONE));
    }

    /** The shared client for the configured key (made if needed), or null when no key is set. */
    private synchronized YouTubeApi lookupApi() {
        String key = config.get().getYouTubeApiKey();
        if (key.isEmpty()) return null;
        if (ytApi == null || !key.equals(ytApiKey)) {
            if (ytPacer != null && ytApi != null) return ytApi;   // a running source keeps its client until restart
            closeYouTubeApi();
            ytApi = new YouTubeApi(key);
            ytApiKey = key;
        }
        return ytApi;
    }

    /** Same rule as stopListener: no idle HttpClient once YouTube is off and nothing else uses it. */
    private synchronized void releaseLookupApi(YouTubeApi used) {
        if (ytApi == used && ytPacer == null && !config.get().youTubeConfigured()) closeYouTubeApi();
    }

    /** The started Twitch client, or null. Lock-free (volatile read). */
    private TwitchMembershipClient twitch() {
        for (ChatSource s : sources) if (s instanceof TwitchMembershipClient t) return t;
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
        World w = PenTicker.resolveWorld(config.get());
        if (w == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(w, () -> task.accept(w)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    @Override
    public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
        Universe u = Universe.get();
        java.util.UUID wu = sender.getWorldUuid(); // nullable mid-transfer
        World w = (u == null || wu == null) ? null : u.getWorld(wu);
        if (w == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(w, () -> task.accept(w)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    /** world.execute that cannot escape: a world mid-unload rejects tasks. @return false if rejected. */
    @Override
    public boolean runOnWorld(World w, Runnable task) {
        try { w.execute(task); return true; }
        catch (RuntimeException e) { bridgeLogger.log(Level.WARNING, "Sproutwatch: world rejected task (unloading?)", e); return false; }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** Restart safety: once per boot, sweep leftover younglings out of the pen. World thread. */
    private void bootSweep(World w) {
        if (!bootSweepDone.compareAndSet(false, true)) return;
        PenClearer.clear(w, registry, roleSet(), PenBounds.fromConfig(config.get()), bridgeLogger);
    }

    /**
     * Bridges java.util.logging (used by every component) onto this plugin's HytaleLogger, a
     * Flogger with only the fluent at(Level) API. Copied from Subinator (verified on 0.6.3).
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
                    String msg = formatter.formatMessage(r);
                    var api = getLogger().at(r.getLevel());
                    if (r.getThrown() != null) api = api.withCause(r.getThrown());
                    api.log("%s", msg);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            jul.addHandler(bridgeHandler);
        }
        return jul;
    }
}
