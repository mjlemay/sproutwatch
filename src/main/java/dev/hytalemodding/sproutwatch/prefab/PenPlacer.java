package dev.hytalemodding.sproutwatch.prefab;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pastes the bundled pen prefab centered on a player, records the pen in config and hands back the
 * ground the paste replaced (for Remove pen).
 * Verified against Server-0.6.3.jar: SelectionPrefabSerializer.deserialize(BsonDocument) reads
 * exactly the bundled JSON format; BlockSelection.placeNoReturn(World, Vector3i, ComponentAccessor)
 * puts prefab-local block (lx,ly,lz) at world (lx + selection.getX() + pos.x - selection.getAnchorX(), ...).
 * Must run on the world thread.
 *
 * The paste uses BlockSelection.place(CommandSender, World, Vector3ic, BlockMask), which returns the
 * replaced blocks. Verified with javap -c on Server-0.6.3.jar:
 * - It delegates to the full place(...) with DEFAULT_ENTITY_CONSUMER, both booleans false and a
 *   null IntUnaryOperator, so no prefab block is skipped (air included) and block ids are unchanged.
 * - It walks the blocks with World.getBlockBulkRelative using the same offsets as placeNoReturn
 *   (world = local + getX() + pos.x - getAnchorX(), per axis), so the pen lands exactly where the
 *   old placeNoReturn call put it.
 * - The returned selection is new BlockSelection(getBlockCount(), 0) with setAnchor(anchorX, anchorY,
 *   anchorZ) and setPosition(x, y, z) copied from this selection, and each replaced block is added
 *   with addBlockAtLocalPos at the prefab-local key (the bulk updater hands over the original local
 *   x, y, z next to the world x, y, z). So placing the returned selection at the same pos puts every
 *   old block back where it was. Only blocks that differ (id, rotation, filler, support or a block
 *   component) are recorded; fluids the selection itself does not carry are not recorded.
 * - A null CommandSender is never dereferenced on the block and fluid paths (placeBlock and
 *   placeFluid never load it), and a null BlockMask is null-checked before isExcluded, the same nulls
 *   placeNoReturn(World, Vector3i, ComponentAccessor) itself passes down.
 * - It reads and writes chunk components directly (getNonTickingChunk, BlockChunk.setBlock), like
 *   placeNoReturn, so it runs on the world thread.
 * - SelectionPrefabSerializer.serialize(BlockSelection) returns a BsonDocument (the format
 *   deserialize reads), which is what the snapshot stores; see PenTerrainRestorer for the paste back.
 */
public final class PenPlacer {

    private PenPlacer() {}

    /**
     * What a paste did: the reply for the caller and the ground it replaced.
     * @param ground the replaced blocks, to save for Remove pen; null when they could not be serialized (logged)
     */
    public record Placement(String message, PenSnapshot ground) {}

    /**
     * @param feet the block position of the caller's feet (floor of their position)
     * @return the reply for the caller and the replaced ground
     * @throws IOException if the bundled prefab is missing
     */
    public static Placement place(World world, Vector3i feet, UUID worldUuid,
                               SproutwatchConfig config, Logger logger) throws IOException {
        String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(config.getPenPrefab()));
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        PenLayout layout = PenLayout.analyze(blocks, PenPlacer::isSeatBlock);
        BlockSelection selection = SelectionPrefabSerializer.deserialize(BsonDocument.parse(json));

        // Center the interior on the caller; the floor block goes one below their feet.
        int centerLocalX = (int) Math.floor(layout.interiorMinX() + layout.sizeX() / 2.0);
        int centerLocalZ = (int) Math.floor(layout.interiorMinZ() + layout.sizeZ() / 2.0);
        Vector3i pos = new Vector3i(
            feet.x - centerLocalX,
            feet.y - 1 - layout.floorY(),
            feet.z - centerLocalZ);

        BlockSelection replaced = selection.place(null, world, pos, null);

        int ox = selection.getX() + pos.x - selection.getAnchorX();
        int oy = selection.getY() + pos.y - selection.getAnchorY();
        int oz = selection.getZ() + pos.z - selection.getAnchorZ();

        config.setPen(worldUuid.toString(),
            layout.interiorMinX() + ox, layout.floorY() + oy, layout.interiorMinZ() + oz,
            layout.sizeX(), layout.clearHeight(), layout.sizeZ());
        config.setChair(layout.chairFound(),
            layout.chairX() + ox, layout.chairY() + oy, layout.chairZ() + oz);
        config.setPenFacing(layout.facing().key());

        String chair = layout.chairFound()
            ? "chair at " + (layout.chairX() + ox) + "," + (layout.chairY() + oy) + "," + (layout.chairZ() + oz)
            : "NO chair block found in the prefab (camera only via /sproutwatch camera)";
        logger.info("Sproutwatch pen placed: interior min " + (layout.interiorMinX() + ox) + "," + (layout.floorY() + oy) + "," + (layout.interiorMinZ() + oz)
            + " size " + layout.sizeX() + "x" + layout.sizeZ() + ", facing " + layout.facing().key() + ", " + chair);
        // World position of the replaced selection's local (0, 0, 0); its position and anchor are copies of
        // the prefab's, but the serialized form drops the position, so the origin is stored instead.
        // The pen is already pasted and recorded, so a serializer failure only loses the saved ground.
        PenSnapshot ground = null;
        try {
            ground = new PenSnapshot(worldUuid.toString(),
                layout.interiorMinX() + ox, layout.floorY() + oy, layout.interiorMinZ() + oz,
                replaced.getX() + pos.x - replaced.getAnchorX(),
                replaced.getY() + pos.y - replaced.getAnchorY(),
                replaced.getZ() + pos.z - replaced.getAnchorZ(),
                SelectionPrefabSerializer.serialize(replaced));
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not serialize the ground the pen replaced", exception);
        }
        String message = "Pen placed (" + layout.sizeX() + "x" + layout.sizeZ() + ", camera faces " + layout.facing().key() + "); " + chair + ".";
        return new Placement(message, ground);
    }

    /** True when the named block type declares seat mount points (a chair, bench, stool). */
    static boolean isSeatBlock(String name) {
        try {
            int idx = BlockType.getAssetMap().getIndexOrDefault(name, -1);
            if (idx < 0) return false;
            BlockType bt = BlockType.getAssetMap().getAsset(idx);
            return bt != null && bt.getSeats() != null && bt.getSeats().size() > 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
