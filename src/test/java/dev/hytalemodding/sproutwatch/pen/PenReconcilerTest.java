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
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of(), Map.of(), 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.empty(), plan.spawn());
        assertEquals(List.of(), plan.despawn());
    }

    @Test void spawnPicksTheEarliestArrival() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L, "b", 50L), Map.of(), 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.of("b"), plan.spawn());
    }

    @Test void spawnTieBreaksAlphabetically() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("b", 100L, "a", 100L), Map.of(), 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.of("a"), plan.spawn());
    }

    /** /sproutwatch test applies the viewer key at 0L so it is served before every real viewer. */
    @Test void zeroTimestampJumpsTheQueue() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L, "b", 50L, "test", 0L), Map.of(), 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.of("test"), plan.spawn());
    }

    @Test void spawnSkipsViewersAlreadyInThePen() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L), Map.of("a", NOW), 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.empty(), plan.spawn());
        assertEquals(List.of(), plan.despawn());
    }

    @Test void capBlocksSpawn() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L), Map.of("x", NOW), 1, NOW)
            .graceMillis(GRACE).build());
        assertEquals(Optional.empty(), plan.spawn());
    }

    @Test void despawnsOnlyPastTheGraceWindow() {
        Map<String, Long> pen = Map.of("stale", NOW - GRACE - 1, "fresh", NOW - GRACE + 1, "edge", NOW - GRACE);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of(), pen, 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(List.of("stale"), plan.despawn());
    }

    @Test void despawnFreesACapSlotInTheSameTick() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L), Map.of("x", NOW - GRACE - 1), 1, NOW)
            .graceMillis(GRACE).build());
        assertEquals(List.of("x"), plan.despawn());
        assertEquals(Optional.of("a"), plan.spawn());
    }

    @Test void despawnListIsSortedAndOnlyOneSpawnPerTick() {
        Map<String, Long> pen = Map.of("z", 0L, "m", 0L, "c", 0L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("q", 1L, "r", 2L), pen, 30, NOW)
            .graceMillis(GRACE).build());
        assertEquals(List.of("c", "m", "z"), plan.despawn());
        assertEquals(Optional.of("q"), plan.spawn());
    }

    @Test void priorityViewerSpawnsFirst() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L, "b", 50L, "q", 900L), Map.of(), 30, NOW)
            .graceMillis(GRACE).queue(List.of("q")).build());
        assertEquals(Optional.of("q"), plan.spawn());
    }

    @Test void priorityOrderIsFifo() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("x", 500L, "y", 10L), Map.of(), 30, NOW)
            .graceMillis(GRACE).queue(List.of("x", "y")).build());
        assertEquals(Optional.of("x"), plan.spawn());
    }

    @Test void prioritySkipsViewersNotInRosterOrAlreadyInPen() {
        Map<String, Long> roster = Map.of("inpen", 1L, "z", 900L, "a", 5L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, Map.of("inpen", NOW), 30, NOW)
            .graceMillis(GRACE).queue(List.of("gone", "inpen", "z")).build());
        assertEquals(Optional.of("z"), plan.spawn());
    }

    @Test void emptyPriorityFallsBackToEarliest() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 100L, "b", 50L), Map.of(), 30, NOW)
            .graceMillis(GRACE).queue(List.of()).build());
        assertEquals(Optional.of("b"), plan.spawn());
    }

    @Test void priorityStillRespectsTheCap() {
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("q", 1L), Map.of("x", NOW), 1, NOW)
            .graceMillis(GRACE).queue(List.of("q")).build());
        assertEquals(Optional.empty(), plan.spawn());
    }

    // ---- persist: sprouts outlive their viewer; at the cap the longest-gone absent viewer is replaced ----

    @Test void persistKeepsAbsentViewersBelowCap() {
        Map<String, Long> pen = Map.of("gone", NOW - GRACE - 1);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("c", 100L), pen, 30, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).build());
        assertEquals(List.of(), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
    }

    @Test void persistReplacesLongestGoneAtCap() {
        Map<String, Long> pen = Map.of("a", 100L, "b", 200L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("c", 500L), pen, 2, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).build());
        assertEquals(List.of("a"), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
    }

    @Test void persistReplacesGuestsBeforeLongestGoneAbsentViewers() {
        // a: absent since 100, g: guest (present, touched now). Guests go first, alphabetical among guests.
        Map<String, Long> pen = Map.of("a", 100L, "g", NOW, "h", NOW);
        Map<String, Long> roster = Map.of("g", 0L, "h", 0L, "c", 500L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 3, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).guests(Set.of("g", "h")).build());
        assertEquals(List.of("g"), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
    }

    @Test void persistDoesNotReplaceAGuestWithAnotherGuest() {
        Map<String, Long> pen = Map.of("g", NOW);
        Map<String, Long> roster = Map.of("g", 0L, "h", 0L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).guests(Set.of("g", "h")).build());
        assertEquals(List.of(), plan.despawn());
        assertEquals(Optional.empty(), plan.spawn());
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
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 2, NOW)
            .graceMillis(GRACE).queue(List.of("q")).persist(true).guests(Set.of()).quiet(lastActive, 600_000L).build());
        assertEquals(List.of("a"), plan.despawn());
        assertEquals(Optional.of("q"), plan.spawn());
    }

    @Test void quietTimeoutIgnoresUnqueuedCandidates() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "c", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 999_000L, "c", NOW);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).guests(Set.of()).quiet(lastActive, 600_000L).build());
        assertEquals(List.of(), plan.despawn());
        assertEquals(Optional.empty(), plan.spawn());
    }

    @Test void quietTimeoutRespectsTheThresholdAndZeroDisables() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 100_000L, "q", NOW);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of("q")).persist(true).guests(Set.of()).quiet(lastActive, 600_000L).build());
        assertEquals(List.of(), plan.despawn(), "a has been quiet 100s < 600s");
        assertEquals(Optional.empty(), plan.spawn());
        PenPlan off = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of("q")).persist(true).guests(Set.of()).quiet(Map.of("a", 0L, "q", NOW), 0L).build());
        assertEquals(List.of(), off.despawn(), "0 disables the timeout");
    }

    @Test void quietTimeoutAlsoAppliesWithPersistOff() {
        Map<String, Long> pen = Map.of("a", NOW);
        Map<String, Long> roster = Map.of("a", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("a", NOW - 700_000L, "q", NOW);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of("q")).persist(false).guests(Set.of()).quiet(lastActive, 600_000L).build());
        assertEquals(List.of("a"), plan.despawn());
        assertEquals(Optional.of("q"), plan.spawn());
    }

    @Test void guestsAndAbsentViewersStillGoBeforeQuietOnes() {
        Map<String, Long> pen = Map.of("a", 100L, "b", NOW);          // a absent (lastSeen old), b present
        Map<String, Long> roster = Map.of("b", 1L, "q", 2L);
        Map<String, Long> lastActive = Map.of("b", NOW - 999_000L, "q", NOW);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 2, NOW)
            .graceMillis(GRACE).queue(List.of("q")).persist(true).guests(Set.of()).quiet(lastActive, 600_000L).build());
        assertEquals(List.of("a"), plan.despawn(), "the absent viewer goes, not the quiet present one");
    }

    @Test void persistNeverEvictsPresentViewers() {
        Map<String, Long> pen = Map.of("a", 0L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 1L, "c", 2L), pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).build());
        assertEquals(List.of(), plan.despawn());
        assertEquals(Optional.empty(), plan.spawn());
    }

    @Test void persistWithNoCandidateDoesNothing() {
        Map<String, Long> pen = Map.of("a", 0L, "b", 0L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("a", 1L, "b", 2L), pen, 2, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).build());
        assertEquals(List.of(), plan.despawn());
        assertEquals(Optional.empty(), plan.spawn());
    }

    @Test void persistHonorsPriority() {
        Map<String, Long> pen = Map.of("a", 100L);
        Map<String, Long> roster = Map.of("c", 900L, "d", 10L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(roster, pen, 1, NOW)
            .graceMillis(GRACE).queue(List.of("c")).persist(true).build());
        assertEquals(List.of("a"), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
    }

    @Test void nonPersistUnchanged() {
        Map<String, Long> pen = Map.of("gone", NOW - GRACE - 1);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("c", 100L), pen, 30, NOW)
            .graceMillis(GRACE).queue(List.of()).build());
        assertEquals(List.of("gone"), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
        PenPlan explicitPersistOff = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("c", 100L), pen, 30, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(false).build());
        assertEquals(plan, explicitPersistOff);
    }

    @Test void persistLongestGoneTiesBreakByViewerKey() {
        Map<String, Long> pen = Map.of("b", 100L, "a", 100L);
        PenPlan plan = PenReconciler.reconcile(ReconcileRequest.builder(Map.of("c", 900L), pen, 2, NOW)
            .graceMillis(GRACE).queue(List.of()).persist(true).build());
        assertEquals(List.of("a"), plan.despawn());
        assertEquals(Optional.of("c"), plan.spawn());
    }
}
