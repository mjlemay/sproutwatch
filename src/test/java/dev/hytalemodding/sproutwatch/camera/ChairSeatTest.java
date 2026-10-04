package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.protocol.BlockMountType;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChairSeatTest {

    /** Interior x 10..25, z 20..31, floor 64, 4 clear: the guard zone is x 6..29, z 16..35. */
    private static SproutwatchConfig pen() {
        SproutwatchConfig c = new SproutwatchConfigAccess().fresh();
        c.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        return c;
    }

    @Test void anySeatAtThePenCounts() {
        SproutwatchConfig c = pen();
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(17, 65, 18)), "the prefab chair, 2 outside");
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(6, 65, 25)), "a player chair 4 outside the west side");
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(15, 65, 25)), "inside the pen");
    }

    @Test void seatsAwayFromThePenDoNot() {
        SproutwatchConfig c = pen();
        assertFalse(ChairCameraService.isPenSeat(c, new Vector3i(4, 65, 25)), "6 outside");
        assertFalse(ChairCameraService.isPenSeat(c, new Vector3i(15, 80, 25)), "high above");
        assertFalse(ChairCameraService.isPenSeat(c, null), "no seat block");
        assertFalse(ChairCameraService.isPenSeat(new SproutwatchConfigAccess().fresh(), new Vector3i(15, 65, 25)), "no pen placed");
    }

    @Test void seatsAndBedsCountCreatureMountsDoNot() {
        // Chairs, stools, benches, sofas and couches declare "Seats" (Seat); beds declare "Beds" (Bed).
        assertTrue(ChairCameraService.isPenMountType(BlockMountType.Seat));
        assertTrue(ChairCameraService.isPenMountType(BlockMountType.Bed));
        assertFalse(ChairCameraService.isPenMountType(null), "riding a creature has no block mount type");
    }
}
