package dev.hytalemodding.sproutwatch.pen;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import java.util.Random;

/**
 * The pen interior in world coordinates. minX/minZ = interior min corner, floorY = the floor
 * block's y (NPC feet stand at floorY + 1), sizeX/sizeZ = interior size, clearHeight = air
 * blocks above the floor. Pure: derived from config, no world access.
 */
public record PenBounds(int minX, int floorY, int minZ, int sizeX, int sizeZ, int clearHeight) {

    // Record component order is (minX, floorY, minZ, sizeX, sizeZ, clearHeight), so PenSizeY
    // (the config's clear-height field) maps to clearHeight here; the x,z,y argument order below
    // is intentional, not a mistake.
    public static PenBounds fromConfig(SproutwatchConfig c) {
        return new PenBounds(c.getPenX(), c.getPenY(), c.getPenZ(), c.getPenSizeX(), c.getPenSizeZ(), c.getPenSizeY());
    }

    public boolean isSet() {
        return sizeX > 0 && sizeZ > 0;
    }

    public int maxX() { return minX + sizeX - 1; }
    public int maxZ() { return minZ + sizeZ - 1; }
    public double centerX() { return minX + sizeX / 2.0; }
    public double centerZ() { return minZ + sizeZ / 2.0; }
    public double feetY() { return floorY + 1.0; }

    public Vector3d center() {
        return new Vector3d(centerX(), feetY(), centerZ());
    }

    /** Center of a uniformly random interior block, at feet level. */
    public Vector3d randomPoint(Random random) {
        double x = minX + random.nextInt(sizeX) + 0.5;
        double z = minZ + random.nextInt(sizeZ) + 0.5;
        return new Vector3d(x, feetY(), z);
    }

    /** Blocks around the interior (x/z) that still count as "at the pen": covers the fence and the chair. */
    public static final double GUARD_MARGIN = 4.0;

    /** Where a mob hitting a player or a sprout gets removed (PenGuardSystem). */
    public boolean inGuardZone(double x, double y, double z) {
        return isSet() && contains(x, y, z, GUARD_MARGIN);
    }

    /** Inside the interior widened by margin on x/z, from the floor block up to clearHeight + 2. */
    public boolean contains(double x, double y, double z, double margin) {
        return x >= minX - margin && x < minX + sizeX + margin
            && z >= minZ - margin && z < minZ + sizeZ + margin
            && y >= floorY && y <= floorY + clearHeight + 2;
    }
}
