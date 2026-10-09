package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure analysis of a prefab block list (prefab-local coordinates):
 *   floorY       = lowest block y
 *   clearHeight  = highest block y - floorY
 *   interior     = bounding box of "Empty" blocks at floorY + 1 (the fenced air), or, if the
 *                  prefab has no Empty blocks there, the whole bounding box shrunk by one on x/z.
 *                  When the prefab has fence or wall blocks at floorY + 1, only Empty blocks
 *                  strictly inside that ring count, so air the editor saved beside outside seats
 *                  never stretches the pen past its fence
 *   chair        = among blocks the isSeat predicate accepts (runtime: BlockType has Seats), the
 *                  north-most one (lowest z), then the one nearest the interior centre on x,
 *                  then the lower x, so extra benches around the pen never steal the camera seat
 *   facing       = PenFacing.forSeat for the chair against the interior center (NORTH when no chair)
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

        int fenceMinX = Integer.MAX_VALUE, fenceMinZ = Integer.MAX_VALUE;
        int fenceMaxX = Integer.MIN_VALUE, fenceMaxZ = Integer.MIN_VALUE;
        boolean anyFence = false;
        for (PrefabBlock b : blocks) {
            if (b.y() == floorY + 1 && (b.name().contains("Fence") || b.name().contains("Wall"))) {
                anyFence = true;
                fenceMinX = Math.min(fenceMinX, b.x()); fenceMaxX = Math.max(fenceMaxX, b.x());
                fenceMinZ = Math.min(fenceMinZ, b.z()); fenceMaxZ = Math.max(fenceMaxZ, b.z());
            }
        }

        int airMinX = Integer.MAX_VALUE, airMinZ = Integer.MAX_VALUE;
        int airMaxX = Integer.MIN_VALUE, airMaxZ = Integer.MIN_VALUE;
        boolean anyAir = false;
        List<PrefabBlock> seats = new ArrayList<>();
        for (PrefabBlock b : blocks) {
            boolean insideFence = !anyFence
                || (b.x() > fenceMinX && b.x() < fenceMaxX && b.z() > fenceMinZ && b.z() < fenceMaxZ);
            if (b.y() == floorY + 1 && "Empty".equals(b.name()) && insideFence) {
                anyAir = true;
                airMinX = Math.min(airMinX, b.x()); airMaxX = Math.max(airMaxX, b.x());
                airMinZ = Math.min(airMinZ, b.z()); airMaxZ = Math.max(airMaxZ, b.z());
            }
            if (isSeat.test(b.name())) seats.add(b);
        }

        int iMinX, iMinZ, sizeX, sizeZ;
        if (anyAir) {
            iMinX = airMinX; iMinZ = airMinZ;
            sizeX = airMaxX - airMinX + 1; sizeZ = airMaxZ - airMinZ + 1;
        } else {
            iMinX = minX + 1; iMinZ = minZ + 1;
            sizeX = Math.max(1, maxX - minX - 1); sizeZ = Math.max(1, maxZ - minZ - 1);
        }

        double cx = iMinX + sizeX / 2.0;
        double cz = iMinZ + sizeZ / 2.0;
        PrefabBlock chair = null;
        for (PrefabBlock s : seats) {
            if (chair == null || isBetterSeat(s, chair, cx)) chair = s;
        }

        PenFacing facing = PenFacing.NORTH;
        if (chair != null) {
            facing = PenFacing.forSeat(chair.x() + 0.5, chair.z() + 0.5, cx, cz);
        }
        return new PenLayout(iMinX, iMinZ, sizeX, sizeZ, floorY, clearHeight,
            chair != null, chair == null ? 0 : chair.x(), chair == null ? 0 : chair.y(), chair == null ? 0 : chair.z(),
            facing);
    }

    private static boolean isBetterSeat(PrefabBlock a, PrefabBlock b, double cx) {
        if (a.z() != b.z()) return a.z() < b.z();
        double da = Math.abs(a.x() + 0.5 - cx), db = Math.abs(b.x() + 0.5 - cx);
        if (da != db) return da < db;
        return a.x() < b.x();
    }
}
