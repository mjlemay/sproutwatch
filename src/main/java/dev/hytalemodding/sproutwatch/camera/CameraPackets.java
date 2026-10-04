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

    public static SetServerCamera penCamera(SproutwatchConfig cfg) {
        PenBounds bounds = PenBounds.fromConfig(cfg);
        PenCamera cam = PenCamera.of(bounds, PenFacing.parse(cfg.getPenFacing()), cfg.getCameraHeight(), cfg.getCameraBack());
        return penCamera(cam, cfg.getCameraFov(), cfg.isCameraFlip());
    }

    public static SetServerCamera penCamera(PenCamera cam, double fov, boolean flip) {
        Rotation3f look = flip
            ? Rotation3f.lookAt(cam.lookAt(), cam.position())
            : Rotation3f.lookAt(cam.position(), cam.lookAt());
        Direction dir = PositionUtil.toDirectionPacket(look);

        ServerCameraSettings s = new ServerCameraSettings();
        s.positionLerpSpeed = 0.2f;
        s.rotationLerpSpeed = 0.2f;
        s.isFirstPerson = false;
        s.displayCursor = false;
        s.eyeOffset = false;
        s.attachedToType = AttachedToType.None;
        s.positionType = PositionType.Custom;
        s.position = new Position(cam.position().x, cam.position().y, cam.position().z);
        s.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        s.rotationType = RotationType.Custom;
        s.rotation = dir;
        s.movementForceRotationType = MovementForceRotationType.Custom;
        s.movementForceRotation = dir;
        s.canMoveType = CanMoveType.Always;
        s.baseFov = (float) fov;
        return new SetServerCamera(ClientCameraView.Custom, true, s);
    }

    /** Identical bytes to /camera reset and CameraManager.resetCamera. */
    public static SetServerCamera reset() {
        return new SetServerCamera(ClientCameraView.Custom, false, null);
    }
}
