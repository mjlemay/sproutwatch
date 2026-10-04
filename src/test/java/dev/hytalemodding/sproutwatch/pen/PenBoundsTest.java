package dev.hytalemodding.sproutwatch.pen;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PenBoundsTest {

    // interior min corner (10, floor 64, 20), 12 x 16, 4 blocks of clear air above the floor
    private static final PenBounds B = new PenBounds(10, 64, 20, 12, 16, 4);

    @Test void guardZoneCoversThePenAndTheChairButNotFarAway() {
        // interior x 10..21, z 20..35, floor 64; the chair sits ~2 blocks outside a short side
        assertTrue(B.inGuardZone(15.5, 65.0, 28.5), "inside the pen");
        assertTrue(B.inGuardZone(16.5, 65.0, 18.5), "on the chair, 2 blocks outside");
        assertTrue(B.inGuardZone(6.5, 65.0, 28.5), "4 blocks outside the fence");
        assertFalse(B.inGuardZone(4.5, 65.0, 28.5), "6 blocks outside");
        assertFalse(B.inGuardZone(15.5, 80.0, 28.5), "high above the pen");
    }

    @Test void geometry() {
        assertTrue(B.isSet());
        assertEquals(21, B.maxX());
        assertEquals(35, B.maxZ());
        assertEquals(16.0, B.centerX());
        assertEquals(28.0, B.centerZ());
        assertEquals(65.0, B.feetY());
        assertEquals(new Vector3d(16.0, 65.0, 28.0), B.center());
    }

    @Test void randomPointsLandOnBlockCentresInsideTheInterior() {
        Random r = new Random(42);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            Vector3d p = B.randomPoint(r);
            assertTrue(p.x >= 10.5 && p.x <= 21.5, "x " + p.x);
            assertTrue(p.z >= 20.5 && p.z <= 35.5, "z " + p.z);
            assertEquals(65.0, p.y);
            assertEquals(0.5, p.x - Math.floor(p.x));
            assertEquals(0.5, p.z - Math.floor(p.z));
            minX = Math.min(minX, (int) Math.floor(p.x));
            maxX = Math.max(maxX, (int) Math.floor(p.x));
            minZ = Math.min(minZ, (int) Math.floor(p.z));
            maxZ = Math.max(maxZ, (int) Math.floor(p.z));
        }
        // With seed 42 over 500 samples, the full interior range must be hit; a shrink-by-one
        // (e.g. nextInt(sizeX - 1)) would never reach the true min/max.
        assertEquals(10, minX);
        assertEquals(21, maxX);
        assertEquals(20, minZ);
        assertEquals(35, maxZ);
    }

    @Test void containsUsesMarginHorizontallyAndFloorToClearHeightVertically() {
        assertTrue(B.contains(10.5, 65, 20.5, 0));
        assertTrue(B.contains(21.9, 65, 35.9, 0));
        assertFalse(B.contains(9.5, 65, 20.5, 0));
        assertTrue(B.contains(9.5, 65, 20.5, 1));     // fence tile with margin
        assertFalse(B.contains(16, 63.9, 28, 0));     // below floor
        assertTrue(B.contains(16, 64, 28, 0));        // floor block
        assertTrue(B.contains(16, 70, 28, 0));        // floor + clear + 2
        assertFalse(B.contains(16, 70.1, 28, 0));
    }

    @Test void fromConfigAndUnsetPen() {
        SproutwatchConfig c = new SproutwatchConfigAccess().fresh();
        assertFalse(PenBounds.fromConfig(c).isSet());
        c.setPen("w", 10, 64, 20, 12, 4, 16);
        assertEquals(B, PenBounds.fromConfig(c));
    }
}
