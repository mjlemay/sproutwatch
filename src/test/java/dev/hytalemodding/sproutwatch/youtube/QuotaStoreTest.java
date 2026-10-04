package dev.hytalemodding.sproutwatch.youtube;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotaStoreTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private final QuotaPacerTest.MutableClock clock =
        new QuotaPacerTest.MutableClock(LocalDateTime.of(2026, 10, 3, 12, 0).atZone(LA).toInstant());
    private final SproutwatchConfig cfg = new SproutwatchConfigAccess().fresh();
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final QuotaStore store = new QuotaStore(cfg, saves::incrementAndGet, millis::get);

    @Test void usageIsWrittenToConfigAndSavedAtMostOncePerMinute() {
        QuotaPacer p = new QuotaPacer(6, clock);
        store.attach(p);
        p.recordCall(2);
        assertEquals("2026-10-03", cfg.getYouTubeQuotaDay());
        assertEquals(2, cfg.getYouTubeQuotaUsed());
        assertEquals(1, saves.get(), "the first call saves at once");
        millis.addAndGet(30_000);
        p.recordCall(2);
        assertEquals(4, cfg.getYouTubeQuotaUsed(), "the config always holds the latest usage");
        assertEquals(1, saves.get(), "but the file is not rewritten within the minute");
        millis.addAndGet(30_000);
        p.recordCall(2);
        assertEquals(2, saves.get(), "a minute later it is");
        store.flush(p);
        assertEquals(3, saves.get(), "Stop always saves");
        assertEquals(6, cfg.getYouTubeQuotaUsed());
    }

    @Test void aSecondStartTheSameDayStartsWithTheSavedUsage() {
        QuotaPacer first = new QuotaPacer(6, clock);
        store.attach(first);
        first.recordCall(2);
        first.recordCall(5);
        store.flush(first);

        clock.advance(Duration.ofHours(2));
        QuotaPacer second = new QuotaPacer(6, clock);
        new QuotaStore(cfg, saves::incrementAndGet, millis::get).attach(second);
        assertEquals(7, second.usedToday());
        second.recordCall(2);
        assertEquals(9, cfg.getYouTubeQuotaUsed());
    }

    @Test void usageFromAnotherDayOrABadDateIsIgnored() {
        cfg.setYouTubeQuota("2026-10-02", 500);
        QuotaPacer p = new QuotaPacer(6, clock);
        store.attach(p);
        assertEquals(0, p.usedToday());

        cfg.setYouTubeQuota("yesterday", 500);
        QuotaPacer q = new QuotaPacer(6, clock);
        store.attach(q);
        assertEquals(0, q.usedToday());
    }

    @Test void flushWithoutAPacerDoesNothing() {
        store.flush(null);
        assertEquals(0, saves.get());
    }

    /** Handle lookups while no pacer runs: 1 unit each, added to today's saved usage, a new day starts over. */
    @Test void lookupUnitsAddToTheSavedUsageAndRollTheDay() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 3);
        store.addUnits(1, day);
        assertEquals("2026-10-03", cfg.getYouTubeQuotaDay());
        assertEquals(1, cfg.getYouTubeQuotaUsed());
        assertEquals(1, saves.get());
        store.addUnits(1, day);
        assertEquals(2, cfg.getYouTubeQuotaUsed());
        store.addUnits(1, day.plusDays(1));
        assertEquals("2026-10-04", cfg.getYouTubeQuotaDay());
        assertEquals(1, cfg.getYouTubeQuotaUsed(), "a new quota day starts from this lookup");
        store.addUnits(0, day.plusDays(1));
        assertEquals(1, cfg.getYouTubeQuotaUsed());

        QuotaPacer p = new QuotaPacer(6, new QuotaPacerTest.MutableClock(
            java.time.LocalDateTime.of(2026, 10, 4, 9, 0).atZone(LA).toInstant()));
        store.restoreInto(p);
        assertEquals(1, p.usedToday(), "the next YouTube start restores the lookup's unit");
    }
}
