package dev.hytalemodding.sproutwatch.youtube;

import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import dev.hytalemodding.sproutwatch.chat.SourceLifecycle;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls one YouTube live chat and feeds its authors into the shared {@link ChatRoster} as
 * {@code yt:<channelId>} keys, storing their display names first.
 *
 * Life cycle (state strings, shown on the settings page, which colors by prefix):
 * - {@code connecting (finding stream)}: resolve the stream. A pasted video link/ID wins;
 *       otherwise handle → channel → current live video → active live chat ID.
 * - {@code connecting (no live stream found, retrying)}: nothing live (or NOT_FOUND) — retried
 *       every 60 s, 10 times (10 min); then {@code not live} and the source stops.
 * - {@code connected to YouTube (@handle)} / {@code connected to YouTube (video <id>)}: polling.
 *       The first page only yields its page token (its backlog is skipped, so a restart never
 *       replays old {@code !sprout}s); later pages are applied in order. Every wait comes from
 *       {@link QuotaPacer#nextDelayMillis}.
 * - {@code reconnecting}: a transient failure (network, 5xx, unreadable body, or any unexpected
 *       exception); backoff 5 s doubling to 5 min, reset on the next successful call.
 * - {@code quota exhausted (resets HH:MM)}: the pacer's budget is spent or YouTube answered
 *       quotaExceeded. HH:MM is the reset (midnight Pacific) in the clock's (server's) zone. Unlike
 *       the other give-up states this one waits until the reset and then resumes on its own
 *       (from a fresh first page, skipping backlog), so a long stream is not left dead overnight.
 * - Terminal, source stops ({@link #isRunning()} false, the state stays as the reason):
 *       {@code not live}, {@code API key rejected}, {@code chat ended},
 *       {@code YouTube rejected the request} (a REJECTED request twice in a row: the first one
 *       drops the page token and restarts from a fresh first page).
 * - {@code stopped}: after {@link #stop()}.
 *
 * Every API call's cost is recorded in the pacer, failed calls included. {@link YouTubeApi#liveVideoId}
 * charges its own calls through the pacer: the search, plus the extra videos call it makes to pick
 * the newest stream when the channel has several live at once.
 *
 * Secret hygiene: logs carry {@link YouTubeException} messages (key-free by construction) or
 * exception class names only, never a URL or the key.
 */
public final class YouTubeChatSource implements ChatSource {

    /** Sleeps the poller thread; tests substitute a recording fake. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final long LOOKUP_RETRY_MILLIS = 60_000;
    /** Retries after the first failed lookup (10 × 60 s = 10 min) before giving up as not live. */
    static final int LOOKUP_RETRIES = 10;
    static final long INITIAL_BACKOFF_MILLIS = 5_000;
    static final long MAX_BACKOFF_MILLIS = 300_000;
    /** Margin past the quota reset before polling again. */
    static final long RESET_MARGIN_MILLIS = 1_000;
    static final long JOIN_MILLIS = STOP_JOIN_MILLIS;

    static final String FINDING = "connecting (finding stream)";
    static final String NO_STREAM = "connecting (no live stream found, retrying)";
    static final String NOT_LIVE = "not live";
    static final String RECONNECTING = "reconnecting";
    static final String KEY_REJECTED = "API key rejected";
    static final String CHAT_ENDED = "chat ended";
    static final String REJECTED = "YouTube rejected the request";
    static final String STOPPED = "stopped";

    private static final DateTimeFormatter HOUR_MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final String streamerHandle;      // "@name", or null when a video override is used
    private final String videoOverride; // 11-char ID, or null
    private final YouTubeApi api;
    private final QuotaPacer pacer;
    private final ChatRoster roster;
    private final DisplayNames names;
    private final Logger logger;
    private final Sleeper sleeper;
    private final Clock clock;
    private final String connectedState;

    private final SourceLifecycle lifecycle;
    private volatile Thread thread;

    /**
     * @param handle        the channel's {@code @handle} (or channel URL with one); may be blank when
     *                      {@code videoOverride} is a usable link/ID
     * @param videoOverride nullable: a pasted watch link or video ID; overrides auto-detection
     * @throws IllegalArgumentException when neither a video nor a handle can be parsed
     */
    public YouTubeChatSource(String handle, String videoOverride, YouTubeApi api, QuotaPacer pacer,
                             ChatRoster roster, DisplayNames names, Logger logger) {
        this(handle, videoOverride, api, pacer, roster, names, logger, Thread::sleep, Clock.systemDefaultZone());
    }

    YouTubeChatSource(String handle, String videoOverride, YouTubeApi api, QuotaPacer pacer,
                      ChatRoster roster, DisplayNames names, Logger logger, Sleeper sleeper, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.names = Objects.requireNonNull(names, "names");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycle = new SourceLifecycle(logger, "Sproutwatch YouTube: ", STOPPED);
        Optional<String> video = YouTubeRef.parseVideoId(videoOverride);
        if (video.isPresent()) {
            this.videoOverride = video.get();
            this.streamerHandle = null;
            this.connectedState = "connected to YouTube (video " + this.videoOverride + ")";
        } else {
            this.videoOverride = null;
            this.streamerHandle = YouTubeRef.parseHandle(handle)
                    .orElseThrow(() -> new IllegalArgumentException("no usable YouTube handle or video link"));
            this.connectedState = "connected to YouTube (" + this.streamerHandle + ")";
        }
    }

    @Override
    public void setOnStateChange(Runnable hook) {
        lifecycle.setOnStateChange(hook);
    }

    @Override
    public synchronized void start() {
        if (lifecycle.running()) return;
        long generation = lifecycle.begin(FINDING);
        Thread worker = new Thread(() -> runLoop(generation), "sproutwatch-youtube");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    @Override
    public synchronized void stop() {
        // The old run may no longer change state or touch the roster. Interrupt and join before
        // announcing, so a slow hook cannot delay them.
        lifecycle.end(STOPPED, this::interruptAndJoin);
    }

    private void interruptAndJoin() {
        Thread worker = thread;
        thread = null;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(JOIN_MILLIS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                lifecycle.safeLog(Level.WARNING, "YouTube listener thread did not end within " + JOIN_MILLIS
                        + " ms of stop; it starts no new roster updates (at most one in-flight message may still land)");
            }
        }
    }

    @Override
    public boolean isRunning() {
        return lifecycle.running();
    }

    @Override
    public String getState() {
        return lifecycle.state();
    }

    // ---- loop ----

    /** Per-run mutable state, confined to the poller thread. */
    private static final class Run {
        String channelId;
        String videoId;
        String liveChatId;
        String pageToken;
        boolean primed; // first page read (backlog skipped)
        int lookupMisses;
        int rejectedStreak;
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
    }

    private void runLoop(long generation) {
        Run run = new Run();
        run.videoId = videoOverride;
        try {
            while (lifecycle.live(generation)) {
                long delay;
                try {
                    try {
                        delay = step(generation, run);
                    } catch (YouTubeException exception) {
                        delay = onError(generation, run, exception);
                    }
                } catch (Throwable throwable) {
                    // Never let the thread die silently.
                    if (!lifecycle.live(generation)) break;
                    lifecycle.safeLog(Level.WARNING, "YouTube chat loop failed (" + describe(throwable) + "); retrying in "
                            + (run.backoffMillis / 1000) + "s");
                    delay = nextBackoff(run);
                    try {
                        lifecycle.setState(generation, RECONNECTING);
                    } catch (Throwable ignored) {
                        // hook/logger trouble: the backoff below still applies
                    }
                }
                if (delay < 0 || !lifecycle.live(generation)) break;
                if (delay == 0) continue;
                if (!sleep(generation, delay)) break;
            }
        } finally {
            if (lifecycle.live(generation)) {
                lifecycle.finish(generation, STOPPED);
                lifecycle.safeLog(Level.SEVERE, "YouTube listener thread ended unexpectedly - run /sproutwatch stop then start");
            }
        }
    }

    /**
     * Sleeps {@code delay} ms. A stray interrupt while the run is still live is cleared and the
     * remainder slept. @return false when the run was stopped meanwhile.
     */
    private boolean sleep(long generation, long delay) {
        long end = System.nanoTime() + delay * 1_000_000L;
        long remaining = delay;
        while (true) {
            try {
                sleeper.sleep(remaining);
                return lifecycle.live(generation);
            } catch (InterruptedException interruption) {
                if (!lifecycle.live(generation)) return false;
                Thread.interrupted(); // stray: clear and carry on
                remaining = (end - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) return true;
            }
        }
    }

    /**
     * Exceptions: class name only (a message could quote a request URL). Errors (out of memory,
     * stack overflow, linkage) never carry URLs, so their message and first frame are logged too.
     */
    static String describe(Throwable throwable) {
        if (!(throwable instanceof Error)) return throwable.getClass().getName();
        StackTraceElement[] frames = throwable.getStackTrace();
        return throwable.getClass().getName() + ": " + throwable.getMessage()
                + (frames.length > 0 ? " at " + frames[0] : "");
    }

    /** One step. @return millis to sleep before the next step, 0 for none, negative to end the run. */
    private long step(long generation, Run run) throws YouTubeException {
        if (pacer.exhausted()) return quotaWait(generation, run); // before lookups too
        if (run.liveChatId == null) return resolve(generation, run);

        if (run.pageToken == null) run.primed = false; // no token to continue from: never replay a backlog
        String token = run.primed ? run.pageToken : null;
        ChatPage page;
        try {
            page = api.chatPage(run.liveChatId, token);
        } finally {
            pacer.recordCall(Endpoint.CHAT_MESSAGES.cost());
        }
        if (!lifecycle.live(generation)) return -1; // stopped during the read: the plugin may already have cleared the roster
        run.rejectedStreak = 0;

        if (run.primed) {
            apply(generation, page);
        } else {
            run.primed = true; // first page: backlog skipped, token kept
        }
        if (page.nextPageToken() != null && !page.nextPageToken().isEmpty()) run.pageToken = page.nextPageToken();
        // Only after the page is applied: the hook may block (plugin locks), and a stop() that
        // times out meanwhile must never be followed by a late apply.
        run.backoffMillis = INITIAL_BACKOFF_MILLIS; // after apply: a page that keeps failing keeps backing off
        lifecycle.setState(generation, connectedState);
        if (page.chatEnded()) {
            lifecycle.finish(generation, CHAT_ENDED);
            return -1;
        }
        return pacer.nextDelayMillis(page.pollingIntervalMillis());
    }

    /** No source lock is held here: roster/name suppliers may take plugin locks. */
    private void apply(long generation, ChatPage page) {
        long now = clock.millis();
        for (YouTubeMessage message : page.messages()) {
            if (!lifecycle.live(generation)) return;
            try {
                String key = ViewerKey.youtube(message.channelId());
                // Name first: a tick between the two must never spawn a sprout named "yt:UC…".
                names.put(key, message.displayName());
                roster.apply(new RosterEvent.Chat(key, message.text()), now);
            } catch (RuntimeException exception) {
                try {
                    logger.log(Level.WARNING, "Roster update failed (" + exception.getClass().getName() + ")", exception);
                } catch (RuntimeException ignored) {
                    // keep applying the rest of the page
                }
            }
        }
    }

    /** Resolves the live chat ID; on success polling starts right away (first page, no sleep). */
    private long resolve(long generation, Run run) throws YouTubeException {
        if (run.videoId == null) {
            if (run.channelId == null) {
                try {
                    run.channelId = api.channelIdForHandle(streamerHandle);
                } finally {
                    pacer.recordCall(Endpoint.CHANNELS.cost());
                }
            }
            Optional<String> video = api.liveVideoId(run.channelId, pacer::recordCall);
            if (video.isEmpty()) return lookupMiss(generation, run);
            run.videoId = video.get();
        }
        try {
            run.liveChatId = api.activeLiveChatId(run.videoId);
        } finally {
            pacer.recordCall(Endpoint.VIDEOS.cost());
        }
        run.primed = false;
        run.pageToken = null;
        run.lookupMisses = 0;
        run.rejectedStreak = 0;
        run.backoffMillis = INITIAL_BACKOFF_MILLIS;
        return 0;
    }

    private long lookupMiss(long generation, Run run) {
        if (videoOverride == null) run.videoId = null; // auto-detect again next time
        if (run.lookupMisses >= LOOKUP_RETRIES) {
            lifecycle.finish(generation, NOT_LIVE);
            return -1;
        }
        run.lookupMisses++;
        lifecycle.setState(generation, NO_STREAM);
        return LOOKUP_RETRY_MILLIS;
    }

    /** Waits until the daily quota resets, then resumes from a fresh first page. */
    private long quotaWait(long generation, Run run) {
        Instant reset = pacer.resetsAt();
        lifecycle.setState(generation, "quota exhausted (resets " + HOUR_MINUTE_FORMAT.withZone(clock.getZone()).format(reset) + ")");
        run.primed = false;
        run.pageToken = null;
        run.backoffMillis = INITIAL_BACKOFF_MILLIS;
        long until = Duration.between(clock.instant(), reset).toMillis();
        return Math.max(0, until) + RESET_MARGIN_MILLIS;
    }

    private long onError(long generation, Run run, YouTubeException exception) {
        if (!lifecycle.live(generation)) return -1; // e.g. the "interrupted" failure of a call cut short by stop()
        String phase = run.liveChatId == null ? "lookup" : "chat read";
        switch (exception.kind()) {
            case KEY_INVALID -> {
                lifecycle.safeLog(Level.WARNING, "YouTube " + phase + " failed: " + exception.getMessage());
                lifecycle.finish(generation, KEY_REJECTED);
                return -1;
            }
            case CHAT_ENDED -> {
                lifecycle.safeLog(Level.INFO, "YouTube " + phase + ": " + exception.getMessage());
                lifecycle.finish(generation, CHAT_ENDED);
                return -1;
            }
            case QUOTA_EXCEEDED -> {
                lifecycle.safeLog(Level.WARNING, "YouTube " + phase + " failed: " + exception.getMessage());
                return quotaWait(generation, run);
            }
            case NOT_FOUND -> {
                lifecycle.safeLog(Level.WARNING, "YouTube " + phase + " failed: " + exception.getMessage());
                if (run.liveChatId == null) return lookupMiss(generation, run);
                // The chat vanished mid-stream: find the stream again after a backoff.
                run.liveChatId = null;
                if (videoOverride == null) run.videoId = null;
                lifecycle.setState(generation, RECONNECTING);
                return nextBackoff(run);
            }
            case REJECTED -> {
                lifecycle.safeLog(Level.WARNING, "YouTube " + phase + " failed: " + exception.getMessage());
                if (++run.rejectedStreak >= 2) {
                    lifecycle.finish(generation, REJECTED);
                    return -1;
                }
                // Drop the page token; the next read is a fresh first page (backlog skipped again).
                run.primed = false;
                run.pageToken = null;
                return pacer.nextDelayMillis(0);
            }
            default -> { // TRANSIENT
                lifecycle.setState(generation, RECONNECTING);
                lifecycle.safeLog(Level.WARNING, "YouTube " + phase + " failed: " + exception.getMessage() + "; retrying in "
                        + (run.backoffMillis / 1000) + "s");
                return nextBackoff(run);
            }
        }
    }

    /** @return the current backoff, doubling the next one (capped at 5 min). */
    private static long nextBackoff(Run run) {
        long delay = run.backoffMillis;
        run.backoffMillis = Math.min(run.backoffMillis * 2, MAX_BACKOFF_MILLIS);
        return delay;
    }
}
