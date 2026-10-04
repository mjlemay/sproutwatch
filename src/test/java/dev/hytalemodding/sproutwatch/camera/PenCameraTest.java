package dev.hytalemodding.sproutwatch.camera;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenCameraTest {

    // interior min (10, floor 64, 20), 12 x 16 -> centre (16, 65, 28)
    private static final PenBounds B = new PenBounds(10, 64, 20, 12, 16, 4);
    private static final Vector3d LOOK = new Vector3d(16.0, 65.0, 28.0);

    @Test void southCameraSitsBehindTheMinZSide() {
        PenCamera c = PenCamera.of(B, PenFacing.SOUTH, 14, 4);
        assertEquals(new Vector3d(16.0, 79.0, 16.0), c.position());
        assertEquals(LOOK, c.lookAt());
    }

    @Test void northCameraSitsBehindTheMaxZSide() {
        assertEquals(new Vector3d(16.0, 79.0, 40.0), PenCamera.of(B, PenFacing.NORTH, 14, 4).position());
    }

    @Test void eastCameraSitsBehindTheMinXSide() {
        assertEquals(new Vector3d(6.0, 79.0, 28.0), PenCamera.of(B, PenFacing.EAST, 14, 4).position());
    }

    @Test void westCameraSitsBehindTheMaxXSide() {
        assertEquals(new Vector3d(26.0, 79.0, 28.0), PenCamera.of(B, PenFacing.WEST, 14, 4).position());
    }

    @Test void heightAndBackAreApplied() {
        PenCamera c = PenCamera.of(B, PenFacing.SOUTH, 2.5, 0);
        assertEquals(new Vector3d(16.0, 67.5, 20.0), c.position());
    }
}
