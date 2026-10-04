package dev.hytalemodding.sproutwatch.youtube;

import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;

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
 * <p>Life cycle (state strings, shown on the settings page, which colours by prefix):
 * <ol>
 *   <li>{@code connecting (finding stream)}: resolve the stream. A pasted video link/ID wins;
 *       otherwise handle → channel → current live video → active live chat ID.
 *   <li>{@code connecting (no live stream found, retrying)}: nothing live (or NOT_FOUND) — retried
 *       every 60 s, 10 times (10 min); then {@code not live} and the source stops.
 *   <li>{@code connected to YouTube (@handle)} / {@code connected to YouTube (video <id>)}: polling.
 *       The first page only yields its page token (its backlog is skipped, so a restart never
 *       replays old {@code !sprout}s); later pages are applied in order. Every wait comes from
 *       {@link QuotaPacer#nextDelayMillis}.
 *   <li>{@code reconnecting}: a transient failure (network, 5xx, unreadable body, or any unexpected
 *       exception); backoff 5 s doubling to 5 min, reset on the next successful call.
 *   <li>{@code quota exhausted (resets HH:MM)}: the pacer's budget is spent or YouTube answered
 *       quotaExceeded. HH:MM is the reset (midnight Pacific) in the clock's (server's) zone. Unlike
 *       the other give-up states this one <b>waits</b> until the reset and then resumes on its own
 *       (from a fresh first page, skipping backlog), so a long stream is not left dead overnight.
 *   <li>Terminal, source stops ({@link #isRunning()} false, the state stays as the reason):
 *       {@code not live}, {@code API key rejected}, {@code chat ended},
 *       {@code YouTube rejected the request} (a REJECTED request twice in a row: the first one
 *       drops the page token and restarts from a fresh first page).
 *   <li>{@code stopped}: after {@link #stop()}.
 * </ol>
 *
 * <p>Every API call's cost is recorded in the pacer, failed calls included. When the channel has
 * several live streams, {@link YouTubeApi#liveVideoId} makes one extra videos call that is not
 * visible here and so not recorded (1 unit, covered by the pacer's reserve).
 *
 * <p>Secret hygiene: logs carry {@link YtException} messages (key-free by construction) or
 * exception class names only, never a URL or the key.
 */
public final class YouTubeChatSource implements ChatSource {

    /** Sleeps the poller thread; tests substitute a recording fake. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final long LOOKUP_RETRY_MS = 60_000;
    /** Retries after the first failed lookup (10 × 60 s = 10 min) before giving up as not live. */
    static final int LOOKUP_RETRIES = 10;
    static final long INITIAL_BACKOFF_MS = 5_000;
    static final long MAX_BACKOFF_MS = 300_000;
    /** Margin past the quota reset before polling again. */
    static final long RESET_MARGIN_MS = 1_000;
    static final long JOIN_MS = 1_000;

    static final String FINDING = "connecting (finding stream)";
    static final String NO_STREAM = "connecting (no live stream found, retrying)";
    static final String NOT_LIVE = "not live";
    static final String RECONNECTING = "reconnecting";
    static final String KEY_REJECTED = "API key rejected";
    static final String CHAT_ENDED = "chat ended";
    static final String REJECTED = "YouTube rejected the request";
    static final String STOPPED = "stopped";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final String handle;      // "@name", or null when a video override is used
    private final String videoOverride; // 11-char ID, or null
    private final YouTubeApi api;
    private final QuotaPacer pacer;
    private final ChatRoster roster;
    private final DisplayNames names;
    private final Logger logger;
    private final Sleeper sleeper;
    private final Clock clock;
    private final String connectedState;

    private final Object stateLock = new Object();
    private volatile String state = STOPPED;
    private volatile Runnable onStateChange;
    private volatile boolean running;
    private volatile Thread thread;
    /** Bumped per start; a run whose generation is stale may no longer touch state. */
    private long generation;

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
        Optional<String> video = YouTubeRef.parseVideoId(videoOverride);
        if (video.isPresent()) {
            this.videoOverride = video.get();
            this.handle = null;
            this.connectedState = "connected to YouTube (video " + this.videoOverride + ")";
        } else {
            this.videoOverride = null;
            this.handle = YouTubeRef.parseHandle(handle)
                    .orElseThrow(() -> new IllegalArgumentException("no usable YouTube handle or video link"));
            this.connectedState = "connected to YouTube (" + this.handle + ")";
        }
    }

    @Override
    public void setOnStateChange(Runnable hook) {
        onStateChange = hook;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        long gen;
        synchronized (stateLock) {
            gen = ++generation;
            running = true;
        }
        setState(gen, FINDING);
        Thread t = new Thread(() -> runLoop(gen), "sproutwatch-youtube");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public synchronized void stop() {
        boolean changed;
        synchronized (stateLock) {
            generation++; // the old run may no longer change state or touch the roster
            running = false;
            changed = !STOPPED.equals(state);
            state = STOPPED;
        }
        // Interrupt and join before announcing, so a throwing hook or logger cannot skip them.
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(JOIN_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                safeLog(Level.WARNING, "YouTube listener thread did not end within " + JOIN_MS
                        + " ms of stop; it starts no new roster updates (at most one in-flight message may still land)");
            }
        }
        if (changed) announce(STOPPED);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getState() {
        return state;
    }

    // ---- state ----

    /** Applies {@code next} if run {@code gen} is still current; logs and fires the hook on change. */
    private void setState(long gen, String next) {
        synchronized (stateLock) {
            if (gen != generation || !running || next.equals(state)) return;
            state = next;
        }
        announce(next);
    }

    /** Terminal: the run ends with {@code reason} as its visible state. */
    private void finish(long gen, String reason) {
        synchronized (stateLock) {
            if (gen != generation || !running) return;
            running = false;
            if (reason.equals(state)) return;
            state = reason;
        }
        announce(reason);
    }

    private void announce(String next) {
        safeLog(Level.INFO, "Sproutwatch YouTube: " + next);
        Runnable hook = onStateChange;
        if (hook == null) return;
        try {
            hook.run();
        } catch (RuntimeException e) {
            try {
                logger.log(Level.WARNING, "Sproutwatch state-change hook failed", e);
            } catch (RuntimeException ignored) {
                // a broken logger must not break the state machine
            }
        }
    }

    /** Logs without ever throwing (a broken logger must not kill the poller or skip stop's join). */
    private void safeLog(Level level, String message) {
        try {
            logger.log(level, message);
        } catch (RuntimeException ignored) {
            // nothing sensible left to do
        }
    }

    private boolean live(long gen) {
        synchronized (stateLock) {
            return running && gen == generation;
        }
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
        long backoffMs = INITIAL_BACKOFF_MS;
    }

    private void runLoop(long gen) {
        Run run = new Run();
        run.videoId = videoOverride;
        try {
            while (live(gen)) {
                long delay;
                try {
                    try {
                        delay = step(gen, run);
                    } catch (YtException e) {
                        delay = onError(gen, run, e);
                    }
                } catch (Throwable t) {
                    // Never let the thread die silently.
                    if (!live(gen)) break;
                    safeLog(Level.WARNING, "YouTube chat loop failed (" + describe(t) + "); retrying in "
                            + (run.backoffMs / 1000) + "s");
                    delay = nextBackoff(run);
                    try {
                        setState(gen, RECONNECTING);
                    } catch (Throwable ignored) {
                        // hook/logger trouble: the backoff below still applies
                    }
                }
                if (delay < 0 || !live(gen)) break;
                if (delay == 0) continue;
                if (!sleep(gen, delay)) break;
            }
        } finally {
            if (live(gen)) {
                finish(gen, STOPPED);
                safeLog(Level.SEVERE, "YouTube listener thread ended unexpectedly - run /sproutwatch stop then start");
            }
        }
    }

    /**
     * Sleeps {@code delay} ms. A stray interrupt while the run is still live is cleared and the
     * remainder slept. @return false when the run was stopped meanwhile.
     */
    private boolean sleep(long gen, long delay) {
        long end = System.nanoTime() + delay * 1_000_000L;
        long remaining = delay;
        while (true) {
            try {
                sleeper.sleep(remaining);
                return live(gen);
            } catch (InterruptedException ie) {
                if (!live(gen)) return false;
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
    static String describe(Throwable t) {
        if (!(t instanceof Error)) return t.getClass().getName();
        StackTraceElement[] frames = t.getStackTrace();
        return t.getClass().getName() + ": " + t.getMessage()
                + (frames.length > 0 ? " at " + frames[0] : "");
    }

    /** One step. @return millis to sleep before the next step, 0 for none, negative to end the run. */
    private long step(long gen, Run run) throws YtException {
        if (pacer.exhausted()) return quotaWait(gen, run); // before lookups too
        if (run.liveChatId == null) return resolve(gen, run);

        if (run.pageToken == null) run.primed = false; // no token to continue from: never replay a backlog
        String token = run.primed ? run.pageToken : null;
        ChatPage page;
        try {
            page = api.chatPage(run.liveChatId, token);
        } finally {
            pacer.recordCall(YouTubeApi.costOf("liveChat/messages"));
        }
        if (!live(gen)) return -1; // stopped during the read: the plugin may already have cleared the roster
        run.rejectedStreak = 0;

        if (run.primed) {
            apply(gen, page);
        } else {
            run.primed = true; // first page: backlog skipped, token kept
        }
        if (page.nextPageToken() != null && !page.nextPageToken().isEmpty()) run.pageToken = page.nextPageToken();
        // Only after the page is applied: the hook may block (plugin locks), and a stop() that
        // times out meanwhile must never be followed by a late apply.
        run.backoffMs = INITIAL_BACKOFF_MS; // after apply: a page that keeps failing keeps backing off
        setState(gen, connectedState);
        if (page.chatEnded()) {
            finish(gen, CHAT_ENDED);
            return -1;
        }
        return pacer.nextDelayMillis(page.pollingIntervalMillis());
    }

    /** No source lock is held here: roster/name suppliers may take plugin locks. */
    private void apply(long gen, ChatPage page) {
        long now = clock.millis();
        for (YtMessage msg : page.messages()) {
            if (!live(gen)) return;
            try {
                String key = ViewerKey.youtube(msg.channelId());
                // Name first: a tick between the two must never spawn a sprout named "yt:UC…".
                names.put(key, msg.displayName());
                roster.apply(new RosterEvent.Chat(key, msg.text()), now);
            } catch (RuntimeException e) {
                try {
                    logger.log(Level.WARNING, "Roster update failed (" + e.getClass().getName() + ")", e);
                } catch (RuntimeException ignored) {
                    // keep applying the rest of the page
                }
            }
        }
    }

    /** Resolves the live chat ID; on success polling starts right away (first page, no sleep). */
    private long resolve(long gen, Run run) throws YtException {
        if (run.videoId == null) {
            if (run.channelId == null) {
                try {
                    run.channelId = api.channelIdForHandle(handle);
                } finally {
                    pacer.recordCall(YouTubeApi.costOf("channels"));
                }
            }
            Optional<String> video;
            try {
                video = api.liveVideoId(run.channelId);
            } finally {
                pacer.recordCall(YouTubeApi.costOf("search"));
            }
            if (video.isEmpty()) return lookupMiss(gen, run);
            run.videoId = video.get();
        }
        try {
            run.liveChatId = api.activeLiveChatId(run.videoId);
        } finally {
            pacer.recordCall(YouTubeApi.costOf("videos"));
        }
        run.primed = false;
        run.pageToken = null;
        run.lookupMisses = 0;
        run.rejectedStreak = 0;
        run.backoffMs = INITIAL_BACKOFF_MS;
        return 0;
    }

    private long lookupMiss(long gen, Run run) {
        if (videoOverride == null) run.videoId = null; // auto-detect again next time
        if (run.lookupMisses >= LOOKUP_RETRIES) {
            finish(gen, NOT_LIVE);
            return -1;
        }
        run.lookupMisses++;
        setState(gen, NO_STREAM);
        return LOOKUP_RETRY_MS;
    }

    /** Waits until the daily quota resets, then resumes from a fresh first page. */
    private long quotaWait(long gen, Run run) {
        Instant reset = pacer.resetsAt();
        setState(gen, "quota exhausted (resets " + HH_MM.withZone(clock.getZone()).format(reset) + ")");
        run.primed = false;
        run.pageToken = null;
        run.backoffMs = INITIAL_BACKOFF_MS;
        long until = Duration.between(clock.instant(), reset).toMillis();
        return Math.max(0, until) + RESET_MARGIN_MS;
    }

    private long onError(long gen, Run run, YtException e) {
        if (!live(gen)) return -1; // e.g. the "interrupted" failure of a call cut short by stop()
        String phase = run.liveChatId == null ? "lookup" : "chat read";
        switch (e.kind()) {
            case KEY_INVALID -> {
                safeLog(Level.WARNING, "YouTube " + phase + " failed: " + e.getMessage());
                finish(gen, KEY_REJECTED);
                return -1;
            }
            case CHAT_ENDED -> {
                safeLog(Level.INFO, "YouTube " + phase + ": " + e.getMessage());
                finish(gen, CHAT_ENDED);
                return -1;
            }
            case QUOTA_EXCEEDED -> {
                safeLog(Level.WARNING, "YouTube " + phase + " failed: " + e.getMessage());
                return quotaWait(gen, run);
            }
            case NOT_FOUND -> {
                safeLog(Level.WARNING, "YouTube " + phase + " failed: " + e.getMessage());
                if (run.liveChatId == null) return lookupMiss(gen, run);
                // The chat vanished mid-stream: find the stream again after a backoff.
                run.liveChatId = null;
                if (videoOverride == null) run.videoId = null;
                setState(gen, RECONNECTING);
                return nextBackoff(run);
            }
            case REJECTED -> {
                safeLog(Level.WARNING, "YouTube " + phase + " failed: " + e.getMessage());
                if (++run.rejectedStreak >= 2) {
                    finish(gen, REJECTED);
                    return -1;
                }
                // Drop the page token; the next read is a fresh first page (backlog skipped again).
                run.primed = false;
                run.pageToken = null;
                return pacer.nextDelayMillis(0);
            }
            default -> { // TRANSIENT
                setState(gen, RECONNECTING);
                safeLog(Level.WARNING, "YouTube " + phase + " failed: " + e.getMessage() + "; retrying in "
                        + (run.backoffMs / 1000) + "s");
                return nextBackoff(run);
            }
        }
    }

    /** @return the current backoff, doubling the next one (capped at 5 min). */
    private static long nextBackoff(Run run) {
        long delay = run.backoffMs;
        run.backoffMs = Math.min(run.backoffMs * 2, MAX_BACKOFF_MS);
        return delay;
    }
}
