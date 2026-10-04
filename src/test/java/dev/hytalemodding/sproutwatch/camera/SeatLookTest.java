package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.math.vector.Rotation3f;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeatLookTest {

    // interior x 10..25, z 20..31, floor 64 -> center (18, 26)
    private static final PenBounds PEN = new PenBounds(10, 64, 20, 16, 12, 4);

    @Test void theSeatedLookIsLevelAndFacesThePenCenter() {
        Vector3d seat = new Vector3d(18, 65.6, 17);              // on the chair, south of the pen
        Rotation3f look = SeatLook.levelToward(seat, PEN);
        assertEquals(0f, look.pitch(), 1e-6, "level: no tilt up or down");
        assertEquals(0f, look.roll(), 1e-6);
        Rotation3f expected = Rotation3f.lookAt(seat, new Vector3d(PEN.centerX(), seat.y, PEN.centerZ()));
        assertEquals(expected.yaw(), look.yaw(), 1e-5, "turned toward the pen center");
    }

    @Test void sittingFromAnotherSideTurnsTheOtherWay() {
        float south = SeatLook.levelToward(new Vector3d(18, 65, 17), PEN).yaw();
        float north = SeatLook.levelToward(new Vector3d(18, 65, 35), PEN).yaw();
        assertEquals(Math.PI, Math.abs(south - north), 1e-4, "opposite sides face opposite directions");
    }
}
