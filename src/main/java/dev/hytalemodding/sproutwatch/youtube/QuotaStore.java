package dev.hytalemodding.sproutwatch.youtube;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps YouTube quota usage across restarts: copies a {@link QuotaPacer}'s day and usage into the
 * config ({@code YouTubeQuotaDay} / {@code YouTubeQuotaUsed}) after every API call and restores it
 * into the next pacer on start. The config fields always hold the latest usage; the file itself is
 * saved at most once per {@link #SAVE_INTERVAL_MILLIS} (the first call saves at once) plus on
 * {@link #flush}, so a crash loses at most a minute of usage.
 *
 * <p>Thread-safe: {@link #record} runs on the poller thread (from the pacer's usage listener),
 * {@link #flush} on whichever thread stops the listener. Never takes the plugin's monitor.
 */
public final class QuotaStore {

    public static final long SAVE_INTERVAL_MILLIS = 60_000;

    private final Supplier<SproutwatchConfig> config;
    private final Runnable save;
    private final LongSupplier millis;
    private final Object lock = new Object();
    private long lastSave;
    private boolean saved;

    /**
     * @param save   persists the config (the plugin's async save)
     * @param millis monotonic-enough wall clock in ms (tests pass a fake)
     */
    public QuotaStore(SproutwatchConfig config, Runnable save, LongSupplier millis) {
        this(constant(Objects.requireNonNull(config, "config")), save, millis);
    }

    /** @param config the live config (the plugin's holder may swap the instance on reload) */
    public QuotaStore(Supplier<SproutwatchConfig> config, Runnable save, LongSupplier millis) {
        this.config = Objects.requireNonNull(config, "config");
        this.save = Objects.requireNonNull(save, "save");
        this.millis = Objects.requireNonNull(millis, "millis");
    }

    /** Restores the saved usage into {@code pacer}, then records every later change of it. */
    public void attach(QuotaPacer pacer) {
        restoreInto(pacer);
        pacer.setUsageListener(() -> record(pacer));
    }

    /** Hands the saved usage back to the pacer when the saved day parses (the pacer ignores other days). */
    public void restoreInto(QuotaPacer pacer) {
        try {
            SproutwatchConfig c = config.get();
            pacer.restore(LocalDate.parse(c.getYouTubeQuotaDay()), c.getYouTubeQuotaUsed());
        } catch (DateTimeParseException ignored) {
            // nothing saved yet, or a hand-edited value: start from zero
        }
    }

    /** Copies the usage into the config; saves when the last save is a minute old. */
    public void record(QuotaPacer pacer) {
        boolean due;
        synchronized (lock) {
            copy(pacer);
            long now = millis.getAsLong();
            due = !saved || now - lastSave >= SAVE_INTERVAL_MILLIS;
            if (due) {
                saved = true;
                lastSave = now;
            }
        }
        if (due) save.run();
    }

    /** Copies and saves now (on Stop). A null pacer (YouTube never started) does nothing. */
    public void flush(QuotaPacer pacer) {
        if (pacer == null) return;
        synchronized (lock) {
            copy(pacer);
            saved = true;
            lastSave = millis.getAsLong();
        }
        save.run();
    }

    /**
     * Adds units spent while no pacer runs (an allow/ignore handle lookup) to the saved usage: added to
     * the saved count when it belongs to {@code today}, else today starts from these units. Saved like
     * {@link #record}, so the next YouTube start restores it into its pacer.
     * @param today the current quota day ({@link QuotaPacer#QUOTA_ZONE})
     */
    public void addUnits(int units, LocalDate today) {
        if (units <= 0) return;
        boolean due;
        synchronized (lock) {
            SproutwatchConfig c = config.get();
            int base = today.toString().equals(c.getYouTubeQuotaDay()) ? c.getYouTubeQuotaUsed() : 0;
            c.setYouTubeQuota(today.toString(), base + units);
            long now = millis.getAsLong();
            due = !saved || now - lastSave >= SAVE_INTERVAL_MILLIS;
            if (due) {
                saved = true;
                lastSave = now;
            }
        }
        if (due) save.run();
    }

    private void copy(QuotaPacer pacer) {
        config.get().setYouTubeQuota(pacer.quotaDay().toString(), pacer.usedToday());
    }

    private static Supplier<SproutwatchConfig> constant(SproutwatchConfig c) {
        return () -> c;
    }
}
