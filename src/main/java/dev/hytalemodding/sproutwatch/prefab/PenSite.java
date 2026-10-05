package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

/**
 * Where a placed pen stands, captured from config before the config changes (a move or a removal
 * forgets the pen while the old spot's ground is still being restored, possibly later on another
 * world's thread). Pure.
 *
 * @param worldUuid  the pen world's UUID as config stores it
 * @param interiorX  world x of the interior min corner (config PenX)
 * @param floorY     world y of the floor block (config PenY)
 * @param interiorZ  world z of the interior min corner (config PenZ)
 * @param prefabName the configured pen prefab, used only when no saved ground exists
 */
public record PenSite(String worldUuid, int interiorX, int floorY, int interiorZ, String prefabName) {

    /** Same world and interior corner (the prefab name may differ). */
    public boolean sameSpot(PenSite other) {
        return worldUuid.equals(other.worldUuid())
            && interiorX == other.interiorX() && floorY == other.floorY() && interiorZ == other.interiorZ();
    }

    public static PenSite of(SproutwatchConfig config) {
        return new PenSite(config.getPenWorld(), config.getPenX(), config.getPenY(), config.getPenZ(), config.getPenPrefab());
    }
}
