package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PenReconcilerTest {

    private static final long NOW = 1_000_000L;
    private static final long GRACE = 300_000L;

    @Test void trimReturnsNothingAtOrUnderTheCap() {
        assertEquals(List.of(), PenReconciler.trim(Map.of("a", 1L, "b", 2L), Map.of("a", 1L, "b", 2L), 2));
        assertEquals(List.of(), PenReconciler.trim(Map.of(), Map.of(), 1));
    }

    @Test void trimDropsAbsentViewersLongestGoneFirst() {
        // pen lastSeen: a and b absent (not in roster), c present; cap 1 -> drop a (gone longest), then b
        Map<String, Long> pen = Map.of("a", 10L, "b", 20L, "c", 30L);
        Map<String, Long> roster = Map.of("c", 5L);
        assertEquals(List.of("a", "b"), PenReconciler.trim(pen, roster, 1));
    }

    @Test void trimThenDropsPresentViewersMostRecentlyJoinedFirst() {
        // all present; roster firstSeen: a 100, b 300, c 200; cap 1 -> drop b (newest), then c
        Map<String, Long> pen = Map.of("a", 1L, "b", 1L, "c", 1L);
        Map<String, Long> roster = Map.of("a", 100L, "b", 300L, "c", 200L);
        assertEquals(List.of("b", "c"), PenReconciler.trim(pen, roster, 1));
    }

    @Test void trimTieBreaksAlphabetically() {
        Map<String, Long> pen = Map.of("b", 1L, "a", 1L);
        assertEquals(List.of("a"), PenReconciler.trim(pen, Map.of(), 1));
        assertEquals(List.of("a"), PenReconciler.trim(pen, Map.of("a", 7L, "b", 7L), 1));
    }

    @Test void emptyInputsPlanNothing() {
        PenPlan p = PenReconciler.reconcile(Map.of(), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
        assertEquals(List.of(), p.despawn());
    }

    @Test void spawnPicksTheEarliestArrival() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L, "b", 50L), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("b"), p.spawn());
    }

    @Test void spawnTieBreaksAlphabetically() {
        PenPlan p = PenReconciler.reconcile(Map.of("b", 100L, "a", 100L), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("a"), p.spawn());
    }

    /** /sproutwatch test applies the login at 0L so it is served before every real viewer. */
    @Test void zeroTimestampJumpsTheQueue() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L, "b", 50L, "test", 0L), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("test"), p.spawn());
    }

    @Test void spawnSkipsLoginsAlreadyInThePen() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("a", NOW), 30, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
        assertEquals(List.of(), p.despawn());
    }

    @Test void capBlocksSpawn() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("x", NOW), 1, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void despawnsOnlyPastTheGraceWindow() {
        Map<String, Long> pen = Map.of("stale", NOW - GRACE - 1, "fresh", NOW - GRACE + 1, "edge", NOW - GRACE);
        PenPlan p = PenReconciler.reconcile(Map.of(), pen, 30, GRACE, NOW);
        assertEquals(List.of("stale"), p.despawn());
    }

    @Test void despawnFreesACapSlotInTheSameTick() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("x", NOW - GRACE - 1), 1, GRACE, NOW);
        assertEquals(List.of("x"), p.despawn());
        assertEquals(Optional.of("a"), p.spawn());
    }

    @Test void despawnListIsSortedAndOnlyOneSpawnPerTick() {
        Map<String, Long> pen = Map.of("z", 0L, "m", 0L, "c", 0L);
        PenPlan p = PenReconciler.reconcile(Map.of("q", 1L, "r", 2L), pen, 30, GRACE, NOW);
        assertEquals(List.of("c", "m", "z"), p.despawn());
        assertEquals(Optional.of("q"), p.spawn());
    }

    @Test void priorityLoginSpawnsFirst() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L, "b", 50L, "q", 900L), Map.of(), List.of("q"), 30, GRACE, NOW);
        assertEquals(Optional.of("q"), p.spawn());
    }

    @Test void priorityOrderIsFifo() {
        PenPlan p = PenReconciler.reconcile(Map.of("x", 500L, "y", 10L), Map.of(), List.of("x", "y"), 30, GRACE, NOW);
        assertEquals(Optional.of("x"), p.spawn());
    }

    @Test void prioritySkipsLoginsNotInRosterOrAlreadyInPen() {
        Map<String, Long> roster = Map.of("inpen", 1L, "z", 900L, "a", 5L);
        PenPlan p = PenReconciler.reconcile(roster, Map.of("inpen", NOW), List.of("gone", "inpen", "z"), 30, GRACE, NOW);
        assertEquals(Optional.of("z"), p.spawn());
    }

    @Test void emptyPriorityFallsBackToEarliest() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L, "b", 50L), Map.of(), List.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("b"), p.spawn());
    }

    @Test void priorityStillRespectsTheCap() {
        PenPlan p = PenReconciler.reconcile(Map.of("q", 1L), Map.of("x", NOW), List.of("q"), 1, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
    }

    // ---- persist: sprouts outlive their viewer; at the cap the longest-gone absent viewer is replaced ----

    @Test void persistKeepsAbsentViewersBelowCap() {
        Map<String, Long> pen = Map.of("gone", NOW - GRACE - 1);
        PenPlan p = PenReconciler.reconcile(Map.of("c", 100L), pen, List.of(), 30, GRACE, NOW, true);
        assertEquals(List.of(), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
    }

    @Test void persistReplacesLongestGoneAtCap() {
        Map<String, Long> pen = Map.of("a", 100L, "b", 200L);
        PenPlan p = PenReconciler.reconcile(Map.of("c", 500L), pen, List.of(), 2, GRACE, NOW, true);
        assertEquals(List.of("a"), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
    }

    @Test void persistReplacesGuestsBeforeLongestGoneAbsentViewers() {
        // a: absent since 100, g: guest (present, touched now). Guests go first, alphabetical among guests.
        Map<String, Long> pen = Map.of("a", 100L, "g", NOW, "h", NOW);
        Map<String, Long> roster = Map.of("g", 0L, "h", 0L, "c", 500L);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of(), 3, GRACE, NOW, true, Set.of("g", "h"));
        assertEquals(List.of("g"), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
    }

    @Test void persistDoesNotReplaceAGuestWithAnotherGuest() {
        Map<String, Long> pen = Map.of("g", NOW);
        Map<String, Long> roster = Map.of("g", 0L, "h", 0L);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of(), 1, GRACE, NOW, true, Set.of("g", "h"));
        assertEquals(List.of(), p.despawn());
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void trimDropsGuestsFirst() {
        Map<String, Long> pen = Map.of("a", 10L, "g", 50L, "c", 30L);
        Map<String, Long> roster = Map.of("c", 5L, "g", 0L);
        assertEquals(List.of("g", "a"), PenReconciler.trim(pen, roster, 1, Set.of("g")));
    }

    // ---- quiet timeout: a QUEUED candidate may replace the pen viewer quiet the longest, if quiet >= quietMillis ----

    @Test void queuedCandidateReplacesTheQuietestPresentViewerAfterTheTimeout() {
        Map<String, Long> pen = Map.of("a", NOW, "b", NOW);           // both present (touched now)
        Map<String, Long> roster = Map.of("a", 1L, "b", 2L, "q", 3L);
        Map<String, Long> lastActive = Map.of("a", NOW - 700_000L, "b", NOW - 100L, "q", NOW);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of("q"), 2, GRACE, NOW, true, Set.of(), lastActive, 600_000L);
        assertEquals(List.of("a"), p.despawn());
        assertEquals(Optional.of("q"), p.spawn());
    }

    @Test void quietTimeoutIgnoresUnqueuedCandidates() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "c", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 999_000L, "c", NOW);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of(), 1, GRACE, NOW, true, Set.of(), lastActive, 600_000L);
        assertEquals(List.of(), p.despawn());
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void quietTimeoutRespectsTheThresholdAndZeroDisables() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 100_000L, "q", NOW);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of("q"), 1, GRACE, NOW, true, Set.of(), lastActive, 600_000L);
        assertEquals(List.of(), p.despawn(), "a has been quiet 100s < 600s");
        assertEquals(Optional.empty(), p.spawn());
        PenPlan off = PenReconciler.reconcile(roster, pen, List.of("q"), 1, GRACE, NOW, true, Set.of(),
            Map.of("a", 0L, "q", NOW), 0L);
        assertEquals(List.of(), off.despawn(), "0 disables the timeout");
    }

    @Test void quietTimeoutAlsoAppliesWithPersistOff() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 700_000L, "q", NOW);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of("q"), 1, GRACE, NOW, false, Set.of(), lastActive, 600_000L);
        assertEquals(List.of("a"), p.despawn());
        assertEquals(Optional.of("q"), p.spawn());
    }

    @Test void guestsAndAbsentViewersStillGoBeforeQuietOnes() {
        Map<String, Long> pen = Map.of("a", 100L, "b", NOW);          // a absent (lastSeen old), b present
        Map<String, Long> roster = Map.of("b", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("b", NOW - 999_000L, "q", NOW);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of("q"), 2, GRACE, NOW, true, Set.of(), lastActive, 600_000L);
        assertEquals(List.of("a"), p.despawn(), "the absent viewer goes, not the quiet present one");
    }

    @Test void persistNeverEvictsPresentViewers() {
        Map<String, Long> pen = Map.of("a", 0L);
        PenPlan p = PenReconciler.reconcile(Map.of("a", 1L, "c", 2L), pen, List.of(), 1, GRACE, NOW, true);
        assertEquals(List.of(), p.despawn());
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void persistWithNoCandidateDoesNothing() {
        Map<String, Long> pen = Map.of("a", 0L, "b", 0L);
        PenPlan p = PenReconciler.reconcile(Map.of("a", 1L, "b", 2L), pen, List.of(), 2, GRACE, NOW, true);
        assertEquals(List.of(), p.despawn());
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void persistHonoursPriority() {
        Map<String, Long> pen = Map.of("a", 100L);
        Map<String, Long> roster = Map.of("c", 900L, "d", 10L);
        PenPlan p = PenReconciler.reconcile(roster, pen, List.of("c"), 1, GRACE, NOW, true);
        assertEquals(List.of("a"), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
    }

    @Test void nonPersistUnchanged() {
        Map<String, Long> pen = Map.of("gone", NOW - GRACE - 1);
        PenPlan p = PenReconciler.reconcile(Map.of("c", 100L), pen, List.of(), 30, GRACE, NOW);
        assertEquals(List.of("gone"), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
        PenPlan q = PenReconciler.reconcile(Map.of("c", 100L), pen, List.of(), 30, GRACE, NOW, false);
        assertEquals(p, q);
    }

    @Test void persistLongestGoneTiesBreakByLogin() {
        Map<String, Long> pen = Map.of("b", 100L, "a", 100L);
        PenPlan p = PenReconciler.reconcile(Map.of("c", 900L), pen, List.of(), 2, GRACE, NOW, true);
        assertEquals(List.of("a"), p.despawn());
        assertEquals(Optional.of("c"), p.spawn());
    }
}
