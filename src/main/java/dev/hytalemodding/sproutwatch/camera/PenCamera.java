package dev.hytalemodding.sproutwatch.camera;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;

/**
 * Where the fixed pen camera sits and what it looks at. Pure geometry.
 * The camera is centered on the side opposite to the facing direction,
 * pushed `back` blocks away from the pen and raised `height` blocks above the floor, looking at
 * the pen center at foot level. Tune with config CameraHeight / CameraBack / CameraFov until a
 * 4:3 landscape crop of the game window is filled by the 16x12 pen.
 */
public record PenCamera(Vector3d position, Vector3d lookAt) {

    public static PenCamera of(PenBounds b, PenFacing facing, double height, double back) {
        double cx = b.centerX();
        double cz = b.centerZ();
        double y = b.feetY();
        double px = cx - facing.dx * (b.sizeX() / 2.0 + back);
        double pz = cz - facing.dz * (b.sizeZ() / 2.0 + back);
        return new PenCamera(new Vector3d(px, y + height, pz), new Vector3d(cx, y, cz));
    }
}
