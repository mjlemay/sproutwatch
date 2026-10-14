package dev.hytalemodding.sproutwatch.youtube;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Paces YouTube live-chat reads so a stream fits in the free daily API quota.
 *
 * YouTube suggests polling about every 2 s ({@code pollingIntervalMillis}); at 5 units per read
 * that would spend over 200,000 units a day against a free quota of 10,000. The streamer picks how
 * often chat is checked ({@code YouTubeCheckSeconds}, read live from the supplier, so a change takes
 * effect on the next read without a restart); at the default 20 s the day's chat budget lasts about
 * 10.9 h. One safety net: when what is left today would not last one more hour at that speed, reads
 * slow down so it does, so a stream that runs long degrades instead of going dark. It never polls
 * faster than YouTube asks or than once a second, and never sleeps past the daily refill.
 *
 * - {@link #COST_PER_POLL} = 5: the cost of one chat read, {@link Endpoint#CHAT_MESSAGES}.
 * - {@link #RESERVE} = 200: kept back from the chat budget to cover the start-up lookups
 *       (channels / search / videos), so a spent chat budget never blocks finding the stream again.
 * - Google resets the quota at midnight Pacific time, so the "quota day" is a {@link LocalDate}
 *       in {@link #QUOTA_ZONE} (DST-safe: 23 h and 25 h days are handled by the zone rules).
 *
 * Usage lives in memory only. To survive a plugin restart the caller persists {@link
 * #quotaDay()} and {@link #usedToday()} from a {@link #setUsageListener usage listener} and hands
 * them back via {@link #restore} on start (see {@link QuotaStore}).
 *
 * Thread-safe: the poller thread records calls while the settings page reads usage.
 */
public final class QuotaPacer {
    public static final int DAILY_QUOTA = 10_000;
    public static final int RESERVE = 200;
    public static final int COST_PER_POLL = Endpoint.CHAT_MESSAGES.cost();
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    public static final long MILLIS_PER_HOUR = 3_600_000L;
    /** What is left of today's budget is always stretched over at least this long. */
    public static final long MIN_HORIZON_MILLIS = MILLIS_PER_HOUR;

    private static final long MIN_DELAY_MILLIS = 1_000;
    private static final long DEFAULT_SUGGESTED_MILLIS = 5_000;

    private final int dailyQuota;
    private final int reserve;
    private final int costPerPoll;
    private final IntSupplier checkSeconds;
    private final Clock clock;

    private LocalDate quotaDay;
    private int used;
    private volatile Runnable usageListener;

    /** @param checkSeconds how often to read chat, in seconds (values below 1 count as 1) */
    public QuotaPacer(IntSupplier checkSeconds, Clock clock) {
        this(DAILY_QUOTA, RESERVE, COST_PER_POLL, checkSeconds, clock);
    }

    public QuotaPacer(int dailyQuota, int reserve, int costPerPoll, IntSupplier checkSeconds, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkSeconds = Objects.requireNonNull(checkSeconds, "checkSeconds");
        if (dailyQuota <= 0) {
            throw new IllegalArgumentException("dailyQuota must be > 0: " + dailyQuota);
        }
        if (reserve < 0 || reserve >= dailyQuota) {
            throw new IllegalArgumentException("reserve must be in [0, dailyQuota): " + reserve);
        }
        if (costPerPoll <= 0 || costPerPoll > dailyQuota - reserve) {
            throw new IllegalArgumentException(
                    "costPerPoll must be in [1, dailyQuota - reserve]: " + costPerPoll);
        }
        this.dailyQuota = dailyQuota;
        this.reserve = reserve;
        this.costPerPoll = costPerPoll;
        this.quotaDay = today();
    }

    /**
     * Hours of chat a full day's budget covers when checking every {@code checkSeconds} seconds,
     * at most 24 (the settings page estimate).
     */
    public static double hoursPerDay(int checkSeconds) {
        return Math.min(24.0, readHours(DAILY_QUOTA - RESERVE, COST_PER_POLL, checkSeconds));
    }

    private static double readHours(int units, int costPerPoll, int checkSeconds) {
        return (double) (units / costPerPoll) * Math.max(1, checkSeconds) / 3600.0;
    }

    /**
     * Delay before the next chat read: the largest of the check interval, YouTube's suggestion
     * (0 or less means 5 s), the one-hour stretch and 1 s, capped so it never sleeps past the
     * daily refill.
     */
    public synchronized long nextDelayMillis(long suggestedMillis) {
        rollOver();
        Instant now = clock.instant();
        long suggested = suggestedMillis <= 0 ? DEFAULT_SUGGESTED_MILLIS : suggestedMillis;
        long interval = intervalSeconds() * 1000L;
        long stretch = (long) Math.ceil((double) MIN_HORIZON_MILLIS * costPerPoll / Math.max(1, remaining()));
        long delay = Math.max(Math.max(interval, suggested), Math.max(stretch, MIN_DELAY_MILLIS));
        long untilReset = Duration.between(now, resetInstant()).toMillis();
        return Math.min(delay, Math.max(MIN_DELAY_MILLIS, untilReset));
    }

    /** Hours of chat what is left today covers at the current check interval (ignores the stretch). */
    public synchronized double hoursLeftToday() {
        rollOver();
        return readHours(remaining(), costPerPoll, intervalSeconds());
    }

    private int intervalSeconds() {
        return Math.max(1, checkSeconds.getAsInt());
    }

    /**
     * Called after every {@link #recordCall} and {@link #restore}, on the calling thread and outside
     * this pacer's lock (so it may read the pacer or block briefly). The plugin persists usage from
     * here. Exceptions it throws are dropped. Null removes it.
     */
    public void setUsageListener(Runnable listener) {
        usageListener = listener;
    }

    /** Record spent units (call after every API call with that call's cost). */
    public void recordCall(int units) {
        if (units < 0) {
            throw new IllegalArgumentException("units must be >= 0: " + units);
        }
        synchronized (this) {
            rollOver();
            used = (int) Math.min(Integer.MAX_VALUE, (long) used + units);
        }
        fireUsage();
    }

    /**
     * Restore usage persisted before a restart. Ignored unless {@code quotaDay} is today's date in
     * {@link #QUOTA_ZONE}; {@code used} is clamped to [0, dailyQuota].
     */
    public void restore(LocalDate quotaDay, int used) {
        synchronized (this) {
            rollOver();
            if (this.quotaDay.equals(quotaDay)) {
                this.used = Math.max(this.used, Math.clamp(used, 0, dailyQuota)); // never hand back units already spent
            }
        }
        fireUsage();
    }

    /**
     * Google answered quotaExceeded: today's chat budget is gone whatever the count says (another
     * world or PC on the same key, or a cost that changed), so count it all as used.
     */
    public void markSpent() {
        synchronized (this) {
            rollOver();
            used = Math.max(used, dailyQuota - reserve);
        }
        fireUsage();
    }

    private void fireUsage() {
        Runnable listener = usageListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // the listener owns its failures; recording usage must never fail because of it
        }
    }

    /** The quota day (date in {@link #QUOTA_ZONE}) that {@link #usedToday()} belongs to. */
    public synchronized LocalDate quotaDay() {
        rollOver();
        return quotaDay;
    }

    /** True when another chat read would eat into the reserve. */
    public synchronized boolean exhausted() {
        rollOver();
        return remaining() < costPerPoll;
    }

    /** Next midnight in {@link #QUOTA_ZONE}, when Google resets the daily quota. */
    public synchronized Instant resetsAt() {
        rollOver();
        return resetInstant();
    }

    /** Units a day's chat reads may spend: {@code dailyQuota - reserve}. */
    public int chatBudget() {
        return dailyQuota - reserve;
    }

    /** Units recorded since the last midnight in {@link #QUOTA_ZONE} (including restored usage). */
    public synchronized int usedToday() {
        rollOver();
        return used;
    }

    /** {@code dailyQuota - reserve - used}, never negative. */
    public synchronized int remainingToday() {
        rollOver();
        return remaining();
    }

    private int remaining() {
        return Math.max(0, dailyQuota - reserve - used);
    }

    private Instant resetInstant() {
        return quotaDay.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), QUOTA_ZONE);
    }

    /** Forward only: a clock stepping backwards never resets usage. */
    private void rollOver() {
        LocalDate now = today();
        if (now.isAfter(quotaDay)) {
            quotaDay = now;
            used = 0;
        }
    }
}
