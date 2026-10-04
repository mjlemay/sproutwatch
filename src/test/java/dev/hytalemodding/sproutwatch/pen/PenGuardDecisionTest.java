package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import static dev.hytalemodding.sproutwatch.pen.PenGuardSystem.Verdict.CANCEL;
import static dev.hytalemodding.sproutwatch.pen.PenGuardSystem.Verdict.CANCEL_AND_REMOVE;
import static dev.hytalemodding.sproutwatch.pen.PenGuardSystem.Verdict.NONE;
import static dev.hytalemodding.sproutwatch.pen.PenGuardSystem.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PenGuardDecisionTest {
    // decide(attackerIsNpc, attackerIsSprout, victimIsPlayer, victimIsSprout, victimAtPen)

    @Test void aPenAnimalNeverHurtsAPlayerAnywhereAndIsNotRemoved() {
        assertEquals(CANCEL, decide(true, true, true, false, true));
        assertEquals(CANCEL, decide(true, true, true, false, false), "even away from the pen (an escaped boar)");
    }

    @Test void anOutsideMobHittingAPlayerOrSproutAtThePenIsRemoved() {
        assertEquals(CANCEL_AND_REMOVE, decide(true, false, true, false, true));
        assertEquals(CANCEL_AND_REMOVE, decide(true, false, false, true, true));
    }

    @Test void everythingElseIsNormalCombat() {
        assertEquals(NONE, decide(true, false, true, false, false), "outside mob, away from the pen");
        assertEquals(NONE, decide(false, false, true, false, true), "a player attacking (PvP rules apply)");
        assertEquals(NONE, decide(false, false, false, true, true), "a player hitting a sprout (retirement rule)");
        assertEquals(NONE, decide(true, true, false, true, true), "pen animals among themselves");
        assertEquals(NONE, decide(true, false, false, false, true), "a mob hitting some other mob");
    }
}
