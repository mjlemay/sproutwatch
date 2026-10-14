package dev.hytalemodding.sproutwatch.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuotaPacerTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    /** A clock whose instant the test sets by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return LA;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Instant la(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).atZone(LA).toInstant();
    }

    private static Instant la(int y, int mo, int d, int h, int mi, int s, int millis) {
        return la(y, mo, d, h, mi, s).plusMillis(millis);
    }

    private final MutableClock clock = new MutableClock(la(2026, 10, 3, 12, 0, 0));

    private static final java.util.function.IntSupplier TWENTY_SECONDS = () -> 20;

    @Test
    void defaultsMatchGooglesNumbers() {
        assertEquals(10_000, QuotaPacer.DAILY_QUOTA);
        assertEquals(200, QuotaPacer.RESERVE);
        assertEquals(5, QuotaPacer.COST_PER_POLL);
        assertEquals(LA, QuotaPacer.QUOTA_ZONE);
    }

    @Test
    void freshPacerWaitsTheCheckInterval() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertEquals(20_000, pacer.nextDelayMillis(1_901));
        assertEquals(9_800, pacer.remainingToday());
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void honorsALargerSuggestion() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertEquals(30_000, pacer.nextDelayMillis(30_000));
    }

    @Test
    void readsTheIntervalLiveSoAChangeNeedsNoRestart() {
        java.util.concurrent.atomic.AtomicInteger seconds = new java.util.concurrent.atomic.AtomicInteger(20);
        QuotaPacer pacer = new QuotaPacer(seconds::get, clock);
        assertEquals(20_000, pacer.nextDelayMillis(1));
        seconds.set(45);
        assertEquals(45_000, pacer.nextDelayMillis(1));
    }

    @Test
    void neverFasterThanOneSecond() {
        QuotaPacer pacer = new QuotaPacer(1_000_000, 0, 1, () -> 0, clock);
        assertEquals(1_000, pacer.nextDelayMillis(10));
        assertEquals(1_000, pacer.nextDelayMillis(1));
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-5000"})
    void nonPositiveSuggestionIsTreatedAsFiveSeconds(long suggested) {
        QuotaPacer pacer = new QuotaPacer(1_000_000, 0, 1, () -> 1, clock);
        assertEquals(5_000, pacer.nextDelayMillis(suggested));
    }

    @Test
    void lastHourOfBudgetIsStretchedToLastAnHour() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(9_000); // 800 units left: 160 reads, under 180 an hour at 20 s
        assertEquals(22_500, pacer.nextDelayMillis(1)); // 1 h * 5 / 800
        pacer.recordCall(400); // 400 left
        assertEquals(45_000, pacer.nextDelayMillis(1));
    }

    @Test
    void exhaustedExactlyWhenRemainingBelowCostPerPoll() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(9_794);
        assertEquals(6, pacer.remainingToday());
        assertFalse(pacer.exhausted());
        pacer.recordCall(1);
        assertEquals(5, pacer.remainingToday());
        assertFalse(pacer.exhausted());
        pacer.recordCall(1);
        assertEquals(4, pacer.remainingToday());
        assertTrue(pacer.exhausted());
    }

    @Test
    void remainingNeverNegativeAndDelayStaysFinite() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(20_000);
        assertEquals(0, pacer.remainingToday());
        assertTrue(pacer.exhausted());
        long delay = pacer.nextDelayMillis(0);
        assertTrue(delay >= 1_000, "delay " + delay);
        assertTrue(delay <= Duration.between(clock.instant(), pacer.resetsAt()).toMillis(), "delay " + delay);
    }

    @Test
    void twentySecondChecksRunFlatForNineHoursThenStretchWithoutOverspending() {
        clock.set(la(2026, 10, 3, 8, 0, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        long elapsed = 0;
        while (elapsed < 9 * 3_600_000L) { // 9 h of reads, suggestion 2 s
            long gap = pacer.nextDelayMillis(2_000);
            assertEquals(20_000, gap, "flat at the interval until the last hour of budget");
            pacer.recordCall(QuotaPacer.COST_PER_POLL);
            clock.advance(Duration.ofMillis(gap));
            elapsed += gap;
        }
        assertEquals(8_100, pacer.usedToday());
        // The rest stretches: still reading at 22:00 (14 h in), never over budget.
        long lastGap = 0;
        while (clock.instant().isBefore(la(2026, 10, 3, 22, 0, 0))) {
            assertFalse(pacer.exhausted(), "exhausted at " + clock.instant());
            lastGap = pacer.nextDelayMillis(2_000);
            pacer.recordCall(QuotaPacer.COST_PER_POLL);
            clock.advance(Duration.ofMillis(lastGap));
        }
        assertTrue(lastGap > 20_000, "slowed down: " + lastGap);
        assertTrue(pacer.usedToday() <= QuotaPacer.DAILY_QUOTA - QuotaPacer.RESERVE);
    }

    @Test
    void hoursPerDayEstimate() {
        assertEquals(10.89, QuotaPacer.hoursPerDay(20), 0.01); // 1,960 reads * 20 s
        assertEquals(5.44, QuotaPacer.hoursPerDay(10), 0.01);
        assertEquals(16.33, QuotaPacer.hoursPerDay(30), 0.01);
        assertEquals(24.0, QuotaPacer.hoursPerDay(60), 0.0); // capped at a day
        assertEquals(24.0, QuotaPacer.hoursPerDay(300), 0.0);
    }

    @Test
    void hoursLeftTodayFollowsRemainingAndInterval() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertEquals(10.89, pacer.hoursLeftToday(), 0.01);
        pacer.recordCall(4_900);
        assertEquals(5.44, pacer.hoursLeftToday(), 0.01);
        pacer.recordCall(10_000);
        assertEquals(0.0, pacer.hoursLeftToday(), 0.0);
    }

    @Test
    void markSpentCountsTheWholeChatBudgetAndFiresTheListener() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        pacer.setUsageListener(() -> seen.add(pacer.usedToday()));
        pacer.recordCall(1_000);
        pacer.markSpent();
        assertEquals(9_800, pacer.usedToday());
        assertTrue(pacer.exhausted());
        assertEquals(java.util.List.of(1_000, 9_800), seen);
        pacer.recordCall(500);
        pacer.markSpent(); // never lowers usage
        assertEquals(10_300, pacer.usedToday());
    }

    @Test
    void neverSleepsPastTheRefill() {
        clock.set(la(2026, 10, 3, 23, 59, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(9_800);
        long delay = pacer.nextDelayMillis(2_000);
        assertTrue(delay <= 60_000 && delay >= 1_000, "delay " + delay);
        clock.set(la(2026, 10, 3, 23, 59, 59, 800));
        assertEquals(1_000, pacer.nextDelayMillis(2_000)); // never faster than 1 s, even right before reset
    }

    @Test
    void delayDropsBackToFreshRightAfterMidnight() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(9_000);
        clock.advance(Duration.ofSeconds(2));
        // a new quota day: back to the plain interval
        assertEquals(20_000, pacer.nextDelayMillis(1));
        assertEquals(9_800, pacer.remainingToday());
    }

    @Test
    void clockSteppingBackwardsDoesNotResetUsage() {
        clock.set(la(2026, 10, 4, 0, 30, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(300);
        clock.advance(Duration.ofHours(-1)); // back across midnight to Oct 3
        assertEquals(300, pacer.usedToday());
        clock.advance(Duration.ofMinutes(10)); // still Oct 3, same-day step
        assertEquals(300, pacer.usedToday());
        clock.set(la(2026, 10, 5, 0, 0, 1)); // forward past the stored day's midnight
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void restoreTodayReducesRemaining() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.restore(LocalDate.of(2026, 10, 3), 1_234);
        assertEquals(1_234, pacer.usedToday());
        assertEquals(9_800 - 1_234, pacer.remainingToday());
        assertEquals(LocalDate.of(2026, 10, 3), pacer.quotaDay());
    }

    @Test
    void restoreYesterdayIsIgnored() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.restore(LocalDate.of(2026, 10, 2), 1_234);
        assertEquals(0, pacer.usedToday());
        pacer.restore(null, 1_234);
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void restoreNeverLowersUsage() {
        // A late restore with a stale saved value must not hand back units already spent this run.
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(500);
        pacer.restore(LocalDate.of(2026, 10, 3), 200);
        assertEquals(500, pacer.usedToday());
        pacer.restore(LocalDate.of(2026, 10, 3), 900);
        assertEquals(900, pacer.usedToday());
    }

    @Test
    void restoreClampsUsage() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.restore(LocalDate.of(2026, 10, 3), -50);
        assertEquals(0, pacer.usedToday());
        pacer.restore(LocalDate.of(2026, 10, 3), 1_000_000);
        assertEquals(10_000, pacer.usedToday());
        assertEquals(0, pacer.remainingToday());
        assertTrue(pacer.exhausted());
    }

    @Test
    void quotaDayRollsOver() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertEquals(LocalDate.of(2026, 10, 3), pacer.quotaDay());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(LocalDate.of(2026, 10, 4), pacer.quotaDay());
    }

    @Test
    void resetsAtIsNextMidnightInLosAngeles() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertEquals(la(2026, 10, 4, 0, 0, 0), pacer.resetsAt());
    }

    @Test
    void usageResetsAfterCrossingMidnightLosAngeles() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(500);
        assertEquals(500, pacer.usedToday());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, pacer.usedToday());
        assertEquals(9_800, pacer.remainingToday());
        assertEquals(la(2026, 10, 5, 0, 0, 0), pacer.resetsAt());
    }

    @Test
    void usageDoesNotResetAtUtcMidnight() {
        // 17:30 LA (PDT) = 00:30 UTC next day.
        clock.set(la(2026, 10, 3, 16, 0, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(100);
        clock.set(la(2026, 10, 3, 17, 30, 0));
        assertEquals(100, pacer.usedToday());
    }

    @ParameterizedTest
    @CsvSource({
        // spring forward: 2027-03-14 is a 23 h day in LA
        "2027, 3, 14, 1, 30, 2027-03-15",
        "2027, 3, 14, 12, 0, 2027-03-15",
        // fall back: 2026-11-01 is a 25 h day in LA
        "2026, 11, 1, 1, 30, 2026-11-02",
        "2026, 11, 1, 23, 0, 2026-11-02",
        // eve of each change
        "2027, 3, 13, 22, 0, 2027-03-14",
        "2026, 10, 31, 22, 0, 2026-11-01",
    })
    void resetsAtIsLocalMidnightAcrossDst(int y, int mo, int d, int h, int mi, LocalDate nextDay) {
        clock.set(la(y, mo, d, h, mi, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        Instant reset = pacer.resetsAt();
        ZonedDateTime local = reset.atZone(LA);
        assertEquals(nextDay, local.toLocalDate());
        assertEquals(0, local.getHour());
        assertEquals(0, local.getMinute());
        assertEquals(nextDay.atStartOfDay(LA).toInstant(), reset);
    }

    @Test
    void fallBackDayIsTwentyFiveHoursLongAndUsageSurvivesTheRepeatedHour() {
        clock.set(la(2026, 11, 1, 0, 0, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(42);
        assertEquals(Duration.ofHours(25), Duration.between(clock.instant(), pacer.resetsAt()));
        clock.advance(Duration.ofHours(24).plusMinutes(59));
        assertEquals(42, pacer.usedToday());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void springForwardDayIsTwentyThreeHoursLong() {
        clock.set(la(2027, 3, 14, 0, 0, 0));
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.recordCall(42);
        assertEquals(Duration.ofHours(23), Duration.between(clock.instant(), pacer.resetsAt()));
        clock.advance(Duration.ofHours(23));
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void constructorValidation() {
        assertThrows(NullPointerException.class, () -> new QuotaPacer(null, clock));
        assertThrows(NullPointerException.class, () -> new QuotaPacer(TWENTY_SECONDS, null));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(0, 0, 2, TWENTY_SECONDS, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, -1, 2, TWENTY_SECONDS, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 10_000, 2, TWENTY_SECONDS, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 200, 0, TWENTY_SECONDS, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 200, 9_801, TWENTY_SECONDS, clock));
    }

    @Test
    void recordCallRejectsNegativeUnits() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        assertThrows(IllegalArgumentException.class, () -> pacer.recordCall(-1));
        pacer.recordCall(0);
        assertEquals(0, pacer.usedToday());
    }

    @Test
    void usageListenerFiresAfterRecordAndRestoreOutsideTheLock() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        java.util.List<Boolean> locked = new java.util.ArrayList<>();
        pacer.setUsageListener(() -> {
            locked.add(Thread.holdsLock(pacer));
            seen.add(pacer.usedToday());
        });
        pacer.recordCall(2);
        pacer.recordCall(3);
        pacer.restore(LocalDate.of(2026, 10, 3), 40);
        assertEquals(java.util.List.of(2, 5, 40), seen);
        assertEquals(java.util.List.of(false, false, false), locked);
        assertThrows(IllegalArgumentException.class, () -> pacer.recordCall(-1));
        assertEquals(3, seen.size(), "a rejected call does not fire");
        pacer.setUsageListener(null);
        pacer.recordCall(1);
        assertEquals(3, seen.size());
    }

    @Test
    void aThrowingUsageListenerDoesNotBreakTheCaller() {
        QuotaPacer pacer = new QuotaPacer(TWENTY_SECONDS, clock);
        pacer.setUsageListener(() -> { throw new RuntimeException("boom"); });
        pacer.recordCall(2);
        assertEquals(2, pacer.usedToday());
    }
}
