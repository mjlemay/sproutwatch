package dev.hytalemodding.sproutwatch.youtube;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Paces YouTube live-chat reads so a whole stream fits in the free daily API quota.
 *
 * <p>YouTube suggests polling about every 2 s ({@code pollingIntervalMillis}); at 2 units per read
 * that would spend ~86,000 units a day against a free quota of 10,000. The pacer spreads what is
 * left of today's chat budget over the planned stream time that is left (the configured stream
 * length minus the time since this pacer was created), but never over less than one hour, so a
 * stream that runs past its plan slows down instead of running dry. It never polls faster than
 * YouTube asks or than once a second, and never sleeps past the daily refill.
 *
 * <ul>
 *   <li>{@link #COST_PER_POLL} = 2: measured 2026-10-03 on the Cloud Console quota page, a chat read
 *       costs 1 or 2 units; budgeting the upper bound keeps us safe.
 *   <li>{@link #RESERVE} = 200: kept back from the chat budget to cover the start-up lookups
 *       (channels / search / videos), so a spent chat budget never blocks finding the stream again.
 *   <li>Google resets the quota at midnight Pacific time, so the "quota day" is a {@link LocalDate}
 *       in {@link #QUOTA_ZONE} (DST-safe: 23 h and 25 h days are handled by the zone rules).
 * </ul>
 *
 * <p>Usage lives in memory only. To survive a plugin restart the caller persists {@link
 * #quotaDay()} and {@link #usedToday()} from a {@link #setUsageListener usage listener} and hands
 * them back via {@link #restore} on start (see {@link QuotaStore}).
 *
 * <p>Thread-safe: the poller thread records calls while the settings page reads usage.
 */
public final class QuotaPacer {
    public static final int DAILY_QUOTA = 10_000;
    public static final int RESERVE = 200;
    public static final int COST_PER_POLL = 2;
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    public static final long MILLIS_PER_HOUR = 3_600_000L;
    /** The remaining budget is never spread over less than this, even past the planned length. */
    public static final long MIN_HORIZON_MILLIS = MILLIS_PER_HOUR;

    private static final long MIN_DELAY_MILLIS = 1_000;
    private static final long DEFAULT_SUGGESTED_MILLIS = 5_000;

    private final int dailyQuota;
    private final int reserve;
    private final int costPerPoll;
    private final double streamHours;
    private final Clock clock;
    private final Instant startedAt;

    private LocalDate quotaDay;
    private int used;
    private volatile Runnable usageListener;

    public QuotaPacer(double streamHours, Clock clock) {
        this(DAILY_QUOTA, RESERVE, COST_PER_POLL, streamHours, clock);
    }

    public QuotaPacer(int dailyQuota, int reserve, int costPerPoll, double streamHours, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (!(streamHours > 0) || Double.isInfinite(streamHours)) {
            throw new IllegalArgumentException("streamHours must be a positive number: " + streamHours);
        }
        this.dailyQuota = dailyQuota;
        this.reserve = reserve;
        this.costPerPoll = costPerPoll;
        this.streamHours = streamHours;
        this.startedAt = clock.instant();
        this.quotaDay = today();
    }

    /**
     * Delay before the next chat read: the larger of YouTube's suggestion (≤ 0 means 5 s), the
     * budget pace and 1 s, capped so it never sleeps past the daily refill.
     */
    public synchronized long nextDelayMillis(long suggestedMillis) {
        rollOver();
        Instant now = clock.instant();
        long suggested = suggestedMillis <= 0 ? DEFAULT_SUGGESTED_MILLIS : suggestedMillis;
        long elapsed = Math.max(0, Duration.between(startedAt, now).toMillis());
        double horizon = Math.max(streamHours * MILLIS_PER_HOUR - elapsed, MIN_HORIZON_MILLIS);
        long budget = (long) Math.ceil(horizon * costPerPoll / Math.max(1, remaining()));
        long delay = Math.max(Math.max(suggested, budget), MIN_DELAY_MILLIS);
        long untilReset = Duration.between(now, resetInstant()).toMillis();
        return Math.min(delay, Math.max(MIN_DELAY_MILLIS, untilReset));
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

    private void fireUsage() {
        Runnable l = usageListener;
        if (l == null) return;
        try {
            l.run();
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
