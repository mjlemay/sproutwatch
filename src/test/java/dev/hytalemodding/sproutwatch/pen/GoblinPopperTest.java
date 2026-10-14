package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GoblinPopperTest {

    private static List<PenRegistry.Entry> entries(int n) {
        List<PenRegistry.Entry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new PenRegistry.Entry("viewer" + i, null, i, null, 0L, 0L));
        return list;
    }

    /** Rolls a full goblin pen many times: how many ticks popped one. */
    private static int pops(String creatures, int max, int live, int ticks) {
        Random random = new Random(42);
        List<PenRegistry.Entry> pen = entries(live);
        int popped = 0;
        for (int i = 0; i < ticks; i++) {
            var victim = GoblinPopper.pick(creatures, max, pen, random);
            if (victim.isPresent()) {
                assertTrue(pen.contains(victim.get()));
                popped++;
            }
        }
        return popped;
    }

    @Test void aFullPenPopsAGoblinAboutOneTickInThree() {
        int popped = pops("goblins", 5, 5, 3000);
        assertTrue(popped > 900 && popped < 1100, "about 1000 of 3000, got " + popped);
    }

    @Test void popsAboveTheCapToo() {
        assertTrue(pops("goblins", 3, 4, 300) > 0);
    }

    @Test void leavesAPenUnderTheCapAlone() {
        assertEquals(0, pops("goblins", 5, 4, 300));
        assertEquals(0, pops("goblins", 5, 0, 300));
    }

    @Test void onlyGoblinsExplode() {
        assertEquals(0, pops("kweebecs", 5, 5, 300));
        assertEquals(0, pops("farm", 5, 5, 300));
    }

    @Test void theBlastPushesNearbyGoblinsAwayAndUp() {
        Vector3d blast = new Vector3d(10, 64, 10);
        Vector3d push = GoblinPopper.knockback(blast, new Vector3d(11, 64, 10), new Random(1)).orElseThrow();
        assertTrue(push.x > 0, "away from the blast, east");
        assertEquals(0.0, push.z, 1e-9);
        assertTrue(push.y > 0, "a little hop");
    }

    @Test void closerGoblinsFlyFarther() {
        Vector3d blast = new Vector3d(0, 0, 0);
        double near = GoblinPopper.knockback(blast, new Vector3d(0, 0, -1), new Random(1)).orElseThrow().length();
        double far = GoblinPopper.knockback(blast, new Vector3d(0, 0, -3), new Random(1)).orElseThrow().length();
        assertTrue(near > far);
    }

    @Test void goblinsOutsideTheRadiusStayPut() {
        assertTrue(GoblinPopper.knockback(new Vector3d(0, 0, 0), new Vector3d(GoblinPopper.BLAST_RADIUS + 0.1, 0, 0), new Random(1)).isEmpty());
    }

    @Test void aGoblinRightOnTheBlastStillGetsPushedSideways() {
        Vector3d push = GoblinPopper.knockback(new Vector3d(5, 5, 5), new Vector3d(5, 5, 5), new Random(1)).orElseThrow();
        assertTrue(Math.hypot(push.x, push.z) > 0);
    }
}
