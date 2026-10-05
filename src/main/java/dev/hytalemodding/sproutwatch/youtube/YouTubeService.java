package dev.hytalemodding.sproutwatch.youtube;

import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The plugin's YouTube side: one shared {@link YouTubeApi} client (made for the configured key,
 * reused, recreated when the key changes, closed off-thread), the running source's {@link QuotaPacer},
 * and the allow/ignore @handle lookups on their own thread. The plugin keeps start/stop orchestration
 * and calls in here for the YouTube steps.
 *
 * Locking: the client fields are guarded by a private lock, never the plugin monitor, so the lookup
 * thread cannot block on a Start or Stop. The plugin may call in while holding its monitor; nothing
 * here calls back into the plugin, so the order is always plugin monitor, then this lock. The pacer
 * is volatile and {@link #currentPacer()} takes no lock (status reads must never block).
 */
public final class YouTubeService {

    private final Supplier<SproutwatchConfig> config;
    private final QuotaStore quotaStore;
    private final Logger logger;
    private final Function<String, YouTubeApi> clientFactory;
    private final Consumer<YouTubeApi> clientCloser;
    /** Allow/ignore @handle lookups: one daemon thread, so a lookup never blocks a world or command thread. */
    private final ExecutorService lookups;
    private final Object lock = new Object();
    /** One shared client, recreated when the key changes. Guarded by {@link #lock}. */
    private YouTubeApi youTubeApi;
    private String youTubeApiKey;
    /** The running YouTube source's pacer (null when YouTube is not started); written under {@link #lock}. */
    private volatile QuotaPacer youTubePacer;

    /** @param config the live config (the plugin's holder may swap the instance on reload) */
    public YouTubeService(Supplier<SproutwatchConfig> config, QuotaStore quotaStore, Logger logger) {
        this(config, quotaStore, logger, YouTubeApi::new, YouTubeApi::close, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sproutwatch-YouTube-lookup");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /**
     * Tests: a fake client factory (keyed by API key), a closer that records its thread, and the lookup executor.
     */
    YouTubeService(Supplier<SproutwatchConfig> config, QuotaStore quotaStore, Logger logger,
                   Function<String, YouTubeApi> clientFactory, Consumer<YouTubeApi> clientCloser, ExecutorService lookups) {
        this.config = Objects.requireNonNull(config, "config");
        this.quotaStore = Objects.requireNonNull(quotaStore, "quotaStore");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.clientCloser = Objects.requireNonNull(clientCloser, "clientCloser");
        this.lookups = Objects.requireNonNull(lookups, "lookups");
    }

    // ---- running source ------------------------------------------------------------------------

    /**
     * Fresh pacer (restored from the saved usage) and source on the shared API client; null if it
     * cannot be built. Records the pacer as the running one.
     */
    public ChatSource newSource(SproutwatchConfig currentConfig, ChatRoster roster, DisplayNames names) {
        YouTubeApi replaced = null;
        try {
            synchronized (lock) {
                String key = currentConfig.getYouTubeApiKey();
                if (youTubeApi == null || !key.equals(youTubeApiKey)) {
                    replaced = detachClient();
                    youTubeApi = clientFactory.apply(key);
                    youTubeApiKey = key;
                }
                QuotaPacer pacer = new QuotaPacer(currentConfig.getYouTubeStreamHours(), Clock.systemDefaultZone());
                quotaStore.attach(pacer);
                ChatSource source = new YouTubeChatSource(currentConfig.getYouTubeHandle(), currentConfig.getYouTubeVideo(), youTubeApi,
                    pacer, roster, names, logger);
                youTubePacer = pacer;
                return source;
            }
        } catch (RuntimeException exception) {
            // Class name only: a message could in theory echo configuration.
            logger.warning("Sproutwatch: YouTube source could not start: " + exception.getClass().getSimpleName());
            return null;
        } finally {
            closeOffThread(replaced);
        }
    }

    /** The source from {@link #newSource} threw on start: it is not running, so neither is its pacer. */
    public void sourceFailedToStart() {
        synchronized (lock) {
            youTubePacer = null;
        }
    }

    /**
     * The listener stopped (its sources have joined): detaches the pacer, saves its usage, and closes
     * the client when YouTube is no longer configured (no idle HttpClient once YouTube is off).
     */
    public void stopped() {
        QuotaPacer pacer;
        synchronized (lock) {
            pacer = youTubePacer;
            youTubePacer = null;
        }
        if (pacer != null) pacer.setUsageListener(null);   // a call still in flight after the join may not save
        quotaStore.flush(pacer);
        if (!config.get().youTubeConfigured()) closeClient();
    }

    /** The running YouTube source's pacer, or null. Lock-free (volatile read), for status reads. */
    public QuotaPacer currentPacer() {
        return youTubePacer;
    }

    // ---- lookups -------------------------------------------------------------------------------

    /**
     * Resolves an allow/ignore @handle on the lookup thread with the shared client (created for the
     * configured key when the listener has not made one). The service lock is only taken on the
     * lookup thread, never the caller's. Each call that reaches the API is counted as 1 quota unit.
     * Failures carry the YouTubeException kind only, never the key.
     */
    public CompletableFuture<String> lookUpViewerChannelId(String viewerHandle) {
        LookupTask task = new LookupTask(viewerHandle);
        try {
            lookups.execute(task);
        } catch (RejectedExecutionException exception) {
            task.future.completeExceptionally(new LookupUnavailable());
        }
        return task.future;
    }

    /** One queued lookup; {@link #shutdownLookups()} fails the future of any task it never ran. */
    private final class LookupTask implements Runnable {
        final String handle;
        final CompletableFuture<String> future = new CompletableFuture<>();

        LookupTask(String handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            try {
                YouTubeApi api = lookupApi();
                if (api == null) throw new NoYouTubeKey();
                try {
                    future.complete(api.channelIdForHandle(handle));
                } finally {
                    countLookupUnit();
                    releaseLookupApi(api);
                }
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }
    }

    /** One channels lookup: into the running pacer (which persists it), else straight into the saved usage. */
    private void countLookupUnit() {
        QuotaPacer pacer = youTubePacer;
        if (pacer != null) pacer.recordCall(Endpoint.CHANNELS.cost());
        else quotaStore.addUnits(Endpoint.CHANNELS.cost(), LocalDate.now(QuotaPacer.QUOTA_ZONE));
    }

    /** The shared client for the configured key (made if needed), or null when no key is set. */
    private YouTubeApi lookupApi() {
        YouTubeApi replaced = null;
        try {
            synchronized (lock) {
                String key = config.get().getYouTubeApiKey();
                if (key.isEmpty()) return null;
                if (youTubeApi == null || !key.equals(youTubeApiKey)) {
                    if (youTubePacer != null && youTubeApi != null) return youTubeApi;   // a running source keeps its client until restart
                    replaced = detachClient();
                    youTubeApi = clientFactory.apply(key);
                    youTubeApiKey = key;
                }
                return youTubeApi;
            }
        } finally {
            closeOffThread(replaced);
        }
    }

    /** Same rule as {@link #stopped()}: no idle HttpClient once YouTube is off and nothing else uses it. */
    private void releaseLookupApi(YouTubeApi used) {
        YouTubeApi detached = null;
        synchronized (lock) {
            if (youTubeApi == used && youTubePacer == null && !config.get().youTubeConfigured()) detached = detachClient();
        }
        closeOffThread(detached);
    }

    // ---- shutdown ------------------------------------------------------------------------------

    /** First shutdown step: queued lookups are failed (LookupUnavailable), an in-flight one is interrupted. */
    public void shutdownLookups() {
        for (Runnable queued : lookups.shutdownNow()) {
            if (queued instanceof LookupTask task) task.future.completeExceptionally(new LookupUnavailable());
        }
    }

    /** Last shutdown step (after the listener stopped): closes the client and waits up to 1 s for the lookup thread. */
    public void close() {
        closeClient();
        try {
            lookups.awaitTermination(1, TimeUnit.SECONDS);   // its quota unit may still land
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- client --------------------------------------------------------------------------------

    /**
     * Detaches the shared client under the lock and closes it on a virtual thread:
     * HttpClient.close() waits for in-flight requests (a lookup or poll, up to the call timeout), and
     * that wait must never hold a lock or block a world or command thread.
     */
    private void closeClient() {
        YouTubeApi api;
        synchronized (lock) {
            api = detachClient();
        }
        closeOffThread(api);
    }

    /** Clears the shared client fields and returns the old client (or null). Caller holds {@link #lock}. */
    private YouTubeApi detachClient() {
        YouTubeApi api = youTubeApi;
        youTubeApi = null;
        youTubeApiKey = null;
        return api;
    }

    private void closeOffThread(YouTubeApi api) {
        if (api != null) Thread.startVirtualThread(() -> clientCloser.accept(api));
    }
}
