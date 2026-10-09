package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.util.PositionUtil;
import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;

/**
 * Builds the two camera packets. Field choices mirror vanilla /camera topdown
 * (PlayerCameraTopdownCommand, read via javap): public fields on ServerCameraSettings, unset
 * enums stay null, packet written with PacketHandler.writeNoCache. The fixed camera uses
 * PositionType.Custom + RotationType.Custom so it never follows the player.
 * Rotation3f.lookAt(eye, target) computes yaw/pitch from target - eye (verified in the decompiled
 * 0.6.3 source), so the default branch passes (position, lookAt); CameraFlip swaps them as a
 * safety toggle only.
 */
public final class CameraPackets {

    private CameraPackets() {}

    public static SetServerCamera penCamera(SproutwatchConfig config) {
        return penCamera(config, PenFacing.parse(config.getPenFacing()));
    }

    /** The pen camera looking {@code facing} (a seated player's own seat side, see PenFacing.forSeat). */
    public static SetServerCamera penCamera(SproutwatchConfig config, PenFacing facing) {
        PenBounds bounds = PenBounds.fromConfig(config);
        PenCamera cam = PenCamera.of(bounds, facing, config.getCameraHeight(), config.getCameraBack());
        return penCamera(cam, config.getCameraFov(), config.isCameraFlip());
    }

    public static SetServerCamera penCamera(PenCamera cam, double fov, boolean flip) {
        Rotation3f look = flip
            ? Rotation3f.lookAt(cam.lookAt(), cam.position())
            : Rotation3f.lookAt(cam.position(), cam.lookAt());
        Direction dir = PositionUtil.toDirectionPacket(look);

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2f;
        settings.rotationLerpSpeed = 0.2f;
        settings.isFirstPerson = false;
        settings.displayCursor = false;
        settings.eyeOffset = false;
        settings.attachedToType = AttachedToType.None;
        settings.positionType = PositionType.Custom;
        settings.position = new Position(cam.position().x, cam.position().y, cam.position().z);
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        settings.rotationType = RotationType.Custom;
        settings.rotation = dir;
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.movementForceRotation = dir;
        settings.canMoveType = CanMoveType.Always;
        settings.baseFov = (float) fov;
        return new SetServerCamera(ClientCameraView.Custom, true, settings);
    }

    /** Identical bytes to /camera reset and CameraManager.resetCamera. */
    public static SetServerCamera reset() {
        return new SetServerCamera(ClientCameraView.Custom, false, null);
    }
}
