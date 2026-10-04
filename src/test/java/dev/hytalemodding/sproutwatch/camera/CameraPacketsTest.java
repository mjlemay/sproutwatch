package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraPacketsTest {

    @Test void theCameraLeavesTheHeadToTheSeatLook() {
        // Camera-side head settings (LookAtPlane, planeNormal, ApplyLookType.Rotation) had no visible effect:
        // the seated head keeps the look the player had when clicking the seat. SeatLook fixes it instead,
        // so the camera packet keeps the engine defaults for those fields.
        PenCamera cam = new PenCamera(new Vector3d(16, 80, 10), new Vector3d(16, 65, 28));
        SetServerCamera packet = CameraPackets.penCamera(cam, 45, false);
        assertEquals(MouseInputType.LookAtTarget, packet.cameraSettings.mouseInputType);
        assertEquals(com.hypixel.hytale.protocol.ApplyLookType.LocalPlayerLookOrientation, packet.cameraSettings.applyLookType);
    }
}
