package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;

import java.util.List;
import java.util.function.Predicate;

/**
 * Pure analysis of a prefab block list (prefab-local coordinates):
 *   floorY       = lowest block y
 *   clearHeight  = highest block y - floorY
 *   interior     = bounding box of "Empty" blocks at floorY + 1 (the fenced air), or, if the
 *                  prefab has no Empty blocks there, the whole bounding box shrunk by one on x/z
 *   chair        = first block the isSeat predicate accepts (runtime: BlockType has Seats)
 *   facing       = direction from the chair toward the interior center (NORTH when no chair)
 */
public record PenLayout(int interiorMinX, int interiorMinZ, int sizeX, int sizeZ,
                        int floorY, int clearHeight,
                        boolean chairFound, int chairX, int chairY, int chairZ,
                        PenFacing facing) {

    public static PenLayout analyze(List<PrefabBlock> blocks, Predicate<String> isSeat) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("Prefab has no blocks");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PrefabBlock b : blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
        }
        int floorY = minY;
        int clearHeight = maxY - minY;

        int airMinX = Integer.MAX_VALUE, airMinZ = Integer.MAX_VALUE;
        int airMaxX = Integer.MIN_VALUE, airMaxZ = Integer.MIN_VALUE;
        boolean anyAir = false;
        PrefabBlock chair = null;
        for (PrefabBlock b : blocks) {
            if (b.y() == floorY + 1 && "Empty".equals(b.name())) {
                anyAir = true;
                airMinX = Math.min(airMinX, b.x()); airMaxX = Math.max(airMaxX, b.x());
                airMinZ = Math.min(airMinZ, b.z()); airMaxZ = Math.max(airMaxZ, b.z());
            }
            if (chair == null && isSeat.test(b.name())) chair = b;
        }

        int iMinX, iMinZ, sizeX, sizeZ;
        if (anyAir) {
            iMinX = airMinX; iMinZ = airMinZ;
            sizeX = airMaxX - airMinX + 1; sizeZ = airMaxZ - airMinZ + 1;
        } else {
            iMinX = minX + 1; iMinZ = minZ + 1;
            sizeX = Math.max(1, maxX - minX - 1); sizeZ = Math.max(1, maxZ - minZ - 1);
        }

        PenFacing facing = PenFacing.NORTH;
        if (chair != null) {
            double cx = iMinX + sizeX / 2.0;
            double cz = iMinZ + sizeZ / 2.0;
            facing = PenFacing.toward(cx - (chair.x() + 0.5), cz - (chair.z() + 0.5));
        }
        return new PenLayout(iMinX, iMinZ, sizeX, sizeZ, floorY, clearHeight,
            chair != null, chair == null ? 0 : chair.x(), chair == null ? 0 : chair.y(), chair == null ? 0 : chair.z(),
            facing);
    }
}
