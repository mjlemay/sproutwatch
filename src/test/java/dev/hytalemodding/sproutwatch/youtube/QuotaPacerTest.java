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

    @Test
    void defaultsMatchTheMeasuredNumbers() {
        assertEquals(10_000, QuotaPacer.DAILY_QUOTA);
        assertEquals(200, QuotaPacer.RESERVE);
        assertEquals(2, QuotaPacer.COST_PER_POLL);
        assertEquals(LA, QuotaPacer.QUOTA_ZONE);
    }

    @Test
    void freshPacerAtSixHoursPacesAboutFourPointFourSeconds() {
        QuotaPacer p = new QuotaPacer(6, clock);
        // startedAt == now, so the horizon is the full 6 h: ceil(6 * 3.6e6 * 2 / 9800) = ceil(4408.16)
        assertEquals(4_409, p.nextDelayMillis(1_901));
        assertEquals(9_800, p.remainingToday());
        assertEquals(0, p.usedToday());
    }

    @Test
    void honoursALargerSuggestion() {
        QuotaPacer p = new QuotaPacer(6, clock);
        assertEquals(10_000, p.nextDelayMillis(10_000));
    }

    @Test
    void neverFasterThanOneSecond() {
        // Huge budget, tiny stream: budget delay is far below 1 s.
        QuotaPacer p = new QuotaPacer(1_000_000, 0, 1, 0.01, clock);
        assertEquals(1_000, p.nextDelayMillis(10));
        assertEquals(1_000, p.nextDelayMillis(1));
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-5000"})
    void nonPositiveSuggestionIsTreatedAsFiveSeconds(long suggested) {
        QuotaPacer p = new QuotaPacer(1_000_000, 0, 1, 0.01, clock);
        assertEquals(5_000, p.nextDelayMillis(suggested));
    }

    @Test
    void delayGrowsAsBudgetIsSpent() {
        QuotaPacer p = new QuotaPacer(6, clock);
        long before = p.nextDelayMillis(1);
        p.recordCall(4_900);
        assertEquals(4_900, p.usedToday());
        assertEquals(4_900, p.remainingToday());
        long after = p.nextDelayMillis(1);
        assertTrue(Math.abs(after - 2 * before) <= 2, before + " -> " + after);
    }

    @Test
    void exhaustedExactlyWhenRemainingBelowCostPerPoll() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(9_797);
        assertEquals(3, p.remainingToday());
        assertFalse(p.exhausted());
        p.recordCall(1);
        assertEquals(2, p.remainingToday());
        assertFalse(p.exhausted());
        p.recordCall(1);
        assertEquals(1, p.remainingToday());
        assertTrue(p.exhausted());
    }

    @Test
    void remainingNeverNegativeAndDelayStaysFinite() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(20_000);
        assertEquals(0, p.remainingToday());
        assertTrue(p.exhausted());
        long delay = p.nextDelayMillis(0);
        assertTrue(delay >= 1_000, "delay " + delay);
        assertTrue(delay <= Duration.between(clock.instant(), p.resetsAt()).toMillis(), "delay " + delay);
    }

    /** Simulated stream: one chat read per delay, suggestion 2 s, recording costPerPoll per read. */
    private record Sim(long gapAtH1, long gapAtH5, long maxGapAfterH6, long minGapAfterH6, int used, boolean exhausted) {}

    private Sim simulate(QuotaPacer p, double actualHours) {
        Instant start = clock.instant();
        long end = (long) (actualHours * 3_600_000);
        long gapAtH1 = -1;
        long gapAtH5 = -1;
        long maxAfterH6 = -1;
        long minAfterH6 = Long.MAX_VALUE;
        long elapsed = 0;
        while (elapsed < end) {
            assertFalse(p.exhausted(), "exhausted at " + elapsed + " ms");
            long gap = p.nextDelayMillis(2_000);
            assertTrue(gap >= 1_000 && gap < 3_600_000, "gap " + gap + " at " + elapsed);
            if (gapAtH1 < 0 && elapsed >= 3_600_000) gapAtH1 = gap;
            if (gapAtH5 < 0 && elapsed >= 5 * 3_600_000) gapAtH5 = gap;
            if (elapsed >= 6 * 3_600_000) {
                maxAfterH6 = Math.max(maxAfterH6, gap);
                minAfterH6 = Math.min(minAfterH6, gap);
            }
            p.recordCall(QuotaPacer.COST_PER_POLL);
            assertTrue(p.usedToday() <= QuotaPacer.DAILY_QUOTA - QuotaPacer.RESERVE, "over budget");
            clock.advance(Duration.ofMillis(gap));
            elapsed = Duration.between(start, clock.instant()).toMillis();
        }
        return new Sim(gapAtH1, gapAtH5, maxAfterH6, minAfterH6, p.usedToday(), p.exhausted());
    }

    @Test
    void sixHourStreamPacesNearlyFlatAndFinishesWithinBudget() {
        clock.set(la(2026, 10, 3, 8, 0, 0));
        Sim s = simulate(new QuotaPacer(6, clock), 6);
        assertTrue(s.gapAtH1() >= 4_300 && s.gapAtH1() <= 4_700, "h1 gap " + s.gapAtH1());
        assertTrue(s.gapAtH5() >= 4_300 && s.gapAtH5() <= 4_700, "h5 gap " + s.gapAtH5());
        assertFalse(s.exhausted());
    }

    @Test
    void streamRunningPastItsPlanSlowsDownButNeverOverspends() {
        clock.set(la(2026, 10, 3, 8, 0, 0));
        Sim s = simulate(new QuotaPacer(6, clock), 10);
        assertTrue(s.minGapAfterH6() > s.gapAtH1(), "after h6 " + s.minGapAfterH6() + " vs h1 " + s.gapAtH1());
        assertTrue(s.maxGapAfterH6() > 0);
        assertTrue(s.used() <= QuotaPacer.DAILY_QUOTA - QuotaPacer.RESERVE);
        assertFalse(s.exhausted());
    }

    @Test
    void horizonUsesPlannedTimeLeftWithAOneHourFloor() {
        clock.set(la(2026, 10, 3, 8, 0, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(9_000); // 800 units left
        clock.advance(Duration.ofHours(3)); // 3 h of the plan left
        assertEquals(27_000, p.nextDelayMillis(1)); // 3 h * 2 / 800
        clock.advance(Duration.ofHours(4)); // past the plan: 1 h floor
        assertEquals(9_000, p.nextDelayMillis(1)); // 1 h * 2 / 800
    }

    @Test
    void neverSleepsPastTheRefill() {
        clock.set(la(2026, 10, 3, 23, 59, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(9_800);
        long delay = p.nextDelayMillis(2_000);
        assertTrue(delay <= 60_000 && delay >= 1_000, "delay " + delay);
        clock.set(la(2026, 10, 3, 23, 59, 59, 800));
        assertEquals(1_000, p.nextDelayMillis(2_000)); // never faster than 1 s, even right before reset
    }

    @Test
    void delayDropsBackToFreshRightAfterMidnight() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(9_000);
        clock.advance(Duration.ofSeconds(2));
        // 2 s of the planned 6 h have elapsed: ceil((21_600_000 - 2_000) * 2 / 9800) = 4408
        assertEquals(4_408, p.nextDelayMillis(1));
        assertEquals(9_800, p.remainingToday());
    }

    @Test
    void clockSteppingBackwardsDoesNotResetUsage() {
        clock.set(la(2026, 10, 4, 0, 30, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(300);
        clock.advance(Duration.ofHours(-1)); // back across midnight to Oct 3
        assertEquals(300, p.usedToday());
        clock.advance(Duration.ofMinutes(10)); // still Oct 3, same-day step
        assertEquals(300, p.usedToday());
        clock.set(la(2026, 10, 5, 0, 0, 1)); // forward past the stored day's midnight
        assertEquals(0, p.usedToday());
    }

    @Test
    void restoreTodayReducesRemaining() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.restore(LocalDate.of(2026, 10, 3), 1_234);
        assertEquals(1_234, p.usedToday());
        assertEquals(9_800 - 1_234, p.remainingToday());
        assertEquals(LocalDate.of(2026, 10, 3), p.quotaDay());
    }

    @Test
    void restoreYesterdayIsIgnored() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.restore(LocalDate.of(2026, 10, 2), 1_234);
        assertEquals(0, p.usedToday());
        p.restore(null, 1_234);
        assertEquals(0, p.usedToday());
    }

    @Test
    void restoreNeverLowersUsage() {
        // A late restore with a stale saved value must not hand back units already spent this run.
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(500);
        p.restore(LocalDate.of(2026, 10, 3), 200);
        assertEquals(500, p.usedToday());
        p.restore(LocalDate.of(2026, 10, 3), 900);
        assertEquals(900, p.usedToday());
    }

    @Test
    void restoreClampsUsage() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.restore(LocalDate.of(2026, 10, 3), -50);
        assertEquals(0, p.usedToday());
        p.restore(LocalDate.of(2026, 10, 3), 1_000_000);
        assertEquals(10_000, p.usedToday());
        assertEquals(0, p.remainingToday());
        assertTrue(p.exhausted());
    }

    @Test
    void quotaDayRollsOver() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer p = new QuotaPacer(6, clock);
        assertEquals(LocalDate.of(2026, 10, 3), p.quotaDay());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(LocalDate.of(2026, 10, 4), p.quotaDay());
    }

    @Test
    void resetsAtIsNextMidnightInLosAngeles() {
        QuotaPacer p = new QuotaPacer(6, clock);
        assertEquals(la(2026, 10, 4, 0, 0, 0), p.resetsAt());
    }

    @Test
    void usageResetsAfterCrossingMidnightLosAngeles() {
        clock.set(la(2026, 10, 3, 23, 59, 59));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(500);
        assertEquals(500, p.usedToday());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, p.usedToday());
        assertEquals(9_800, p.remainingToday());
        assertEquals(la(2026, 10, 5, 0, 0, 0), p.resetsAt());
    }

    @Test
    void usageDoesNotResetAtUtcMidnight() {
        // 17:30 LA (PDT) = 00:30 UTC next day.
        clock.set(la(2026, 10, 3, 16, 0, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(100);
        clock.set(la(2026, 10, 3, 17, 30, 0));
        assertEquals(100, p.usedToday());
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
        QuotaPacer p = new QuotaPacer(6, clock);
        Instant reset = p.resetsAt();
        ZonedDateTime local = reset.atZone(LA);
        assertEquals(nextDay, local.toLocalDate());
        assertEquals(0, local.getHour());
        assertEquals(0, local.getMinute());
        assertEquals(nextDay.atStartOfDay(LA).toInstant(), reset);
    }

    @Test
    void fallBackDayIsTwentyFiveHoursLongAndUsageSurvivesTheRepeatedHour() {
        clock.set(la(2026, 11, 1, 0, 0, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(42);
        assertEquals(Duration.ofHours(25), Duration.between(clock.instant(), p.resetsAt()));
        clock.advance(Duration.ofHours(24).plusMinutes(59));
        assertEquals(42, p.usedToday());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, p.usedToday());
    }

    @Test
    void springForwardDayIsTwentyThreeHoursLong() {
        clock.set(la(2027, 3, 14, 0, 0, 0));
        QuotaPacer p = new QuotaPacer(6, clock);
        p.recordCall(42);
        assertEquals(Duration.ofHours(23), Duration.between(clock.instant(), p.resetsAt()));
        clock.advance(Duration.ofHours(23));
        assertEquals(0, p.usedToday());
    }

    @Test
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(0, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(-1, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(Double.NaN, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(Double.POSITIVE_INFINITY, clock));
        assertThrows(NullPointerException.class, () -> new QuotaPacer(6, null));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(0, 0, 2, 6, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, -1, 2, 6, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 10_000, 2, 6, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 200, 0, 6, clock));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPacer(10_000, 200, 9_801, 6, clock));
    }

    @Test
    void recordCallRejectsNegativeUnits() {
        QuotaPacer p = new QuotaPacer(6, clock);
        assertThrows(IllegalArgumentException.class, () -> p.recordCall(-1));
        p.recordCall(0);
        assertEquals(0, p.usedToday());
    }

    @Test
    void usageListenerFiresAfterRecordAndRestoreOutsideTheLock() {
        QuotaPacer p = new QuotaPacer(6, clock);
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        java.util.List<Boolean> locked = new java.util.ArrayList<>();
        p.setUsageListener(() -> {
            locked.add(Thread.holdsLock(p));
            seen.add(p.usedToday());
        });
        p.recordCall(2);
        p.recordCall(3);
        p.restore(LocalDate.of(2026, 10, 3), 40);
        assertEquals(java.util.List.of(2, 5, 40), seen);
        assertEquals(java.util.List.of(false, false, false), locked);
        assertThrows(IllegalArgumentException.class, () -> p.recordCall(-1));
        assertEquals(3, seen.size(), "a rejected call does not fire");
        p.setUsageListener(null);
        p.recordCall(1);
        assertEquals(3, seen.size());
    }

    @Test
    void aThrowingUsageListenerDoesNotBreakTheCaller() {
        QuotaPacer p = new QuotaPacer(6, clock);
        p.setUsageListener(() -> { throw new RuntimeException("boom"); });
        p.recordCall(2);
        assertEquals(2, p.usedToday());
    }
}
