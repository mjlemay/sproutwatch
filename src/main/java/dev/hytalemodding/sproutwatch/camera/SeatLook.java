package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;

/**
 * A seated player's head keeps the look they had when they clicked the seat (looking down at it),
 * and no camera setting changes that. So on sitting we set the look itself: level, turned toward
 * the pen centre, through the engine's own TeleportSystems.queueAndSendClientTeleport with position
 * and body marked "ignore" (NaN fields set the engine's ignore flags; the ack tracker accepts a NaN
 * expected position), so the player is not moved or unseated.
 */
final class SeatLook {

    private static final Vector3d NO_POSITION = new Vector3d(Double.NaN, Double.NaN, Double.NaN);
    private static final Rotation3f NO_ROTATION = new Rotation3f(Float.NaN, Float.NaN, Float.NaN);

    private SeatLook() {}

    /** Pure: pitch 0 and roll 0, yaw toward the pen centre seen from {@code seat}. */
    static Rotation3f levelToward(Vector3d seat, PenBounds pen) {
        Rotation3f toward = Rotation3f.lookAt(seat, new Vector3d(pen.centerX(), seat.y, pen.centerZ()));
        return new Rotation3f(0f, toward.yaw(), 0f);
    }

    /** Turns only the player's head/look; position and body untouched. */
    static void apply(PlayerRef player, Vector3d seat, PenBounds pen) {
        TeleportSystems.queueAndSendClientTeleport(player, new Vector3d(NO_POSITION), new Rotation3f(NO_ROTATION),
            levelToward(seat, pen), false);
    }
}
