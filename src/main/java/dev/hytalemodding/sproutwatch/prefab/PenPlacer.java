package dev.hytalemodding.sproutwatch.prefab;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Pastes the bundled pen prefab centred on a player and records the pen in config.
 * Verified against Server-0.6.3.jar: SelectionPrefabSerializer.deserialize(BsonDocument) reads
 * exactly the bundled JSON format; BlockSelection.placeNoReturn(World, Vector3i, ComponentAccessor)
 * puts prefab-local block (lx,ly,lz) at world (lx + sel.getX() + pos.x - sel.getAnchorX(), ...).
 * Must run on the world thread.
 */
public final class PenPlacer {

    private PenPlacer() {}

    /**
     * @param feet the block position of the caller's feet (floor of their position)
     * @return a message for the caller
     * @throws IOException if the bundled prefab is missing
     */
    public static String place(World world, Store<EntityStore> store, Vector3i feet, UUID worldUuid,
                               SproutwatchConfig cfg, Logger logger) throws IOException {
        String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(cfg.getPenPrefab()));
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        PenLayout layout = PenLayout.analyze(blocks, PenPlacer::isSeatBlock);
        BlockSelection sel = SelectionPrefabSerializer.deserialize(BsonDocument.parse(json));

        // Centre the interior on the caller; the floor block goes one below their feet.
        int centreLocalX = (int) Math.floor(layout.interiorMinX() + layout.sizeX() / 2.0);
        int centreLocalZ = (int) Math.floor(layout.interiorMinZ() + layout.sizeZ() / 2.0);
        Vector3i pos = new Vector3i(
            feet.x - centreLocalX,
            feet.y - 1 - layout.floorY(),
            feet.z - centreLocalZ);

        sel.placeNoReturn(world, pos, store);

        int ox = sel.getX() + pos.x - sel.getAnchorX();
        int oy = sel.getY() + pos.y - sel.getAnchorY();
        int oz = sel.getZ() + pos.z - sel.getAnchorZ();

        cfg.setPen(worldUuid.toString(),
            layout.interiorMinX() + ox, layout.floorY() + oy, layout.interiorMinZ() + oz,
            layout.sizeX(), layout.clearHeight(), layout.sizeZ());
        cfg.setChair(layout.chairFound(),
            layout.chairX() + ox, layout.chairY() + oy, layout.chairZ() + oz);
        cfg.setPenFacing(layout.facing().key());

        String chair = layout.chairFound()
            ? "chair at " + (layout.chairX() + ox) + "," + (layout.chairY() + oy) + "," + (layout.chairZ() + oz)
            : "NO chair block found in the prefab (camera only via /sproutwatch camera)";
        logger.info("Sproutwatch pen placed: interior min " + (layout.interiorMinX() + ox) + "," + (layout.floorY() + oy) + "," + (layout.interiorMinZ() + oz)
            + " size " + layout.sizeX() + "x" + layout.sizeZ() + ", facing " + layout.facing().key() + ", " + chair);
        return "Pen placed (" + layout.sizeX() + "x" + layout.sizeZ() + ", camera faces " + layout.facing().key() + "); " + chair + ".";
    }

    /** True when the named block type declares seat mount points (a chair, bench, stool). */
    static boolean isSeatBlock(String name) {
        try {
            int idx = BlockType.getAssetMap().getIndexOrDefault(name, -1);
            if (idx < 0) return false;
            BlockType bt = BlockType.getAssetMap().getAsset(idx);
            return bt != null && bt.getSeats() != null && bt.getSeats().size() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
