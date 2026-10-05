package dev.hytalemodding.sproutwatch.prefab;

import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine side of {@link PenTerrain}: pastes the prefab (PenPlacer), keeps the saved ground in a
 * {@link PenSnapshotStore} file, and pastes it back with BlockSelection.place on the pen world thread.
 *
 * Verified against Server-0.6.3.jar with javap -c (see PenPlacer for the capture side):
 * - SelectionPrefabSerializer.serialize(BlockSelection) writes version, blockIdVersion, the anchor
 *   and every block at its local key (forEachBlock unpacks the map key); the selection's position
 *   is not written, so deserialize returns position (0, 0, 0) with the anchor restored. That is why
 *   the snapshot stores the world origin of local (0, 0, 0) and the paste position is computed from
 *   the deserialized selection here.
 * - Air is written like any block (name = the asset id of block 0), and place(null, world, pos,
 *   null) places every block including air (both skip flags are false), so ground that was air
 *   before the paste becomes air again.
 * - The paste back uses place(CommandSender, World, Vector3ic, BlockMask), not placeNoReturn,
 *   because only place writes each block's support value (BlockPhysics.set in placeBlock), which
 *   the snapshot carries. Its return value can be ignored: it is a fresh BlockSelection held only
 *   in a local (never stored in a field). Otherwise the two paths do the same: same offsets, same
 *   setBlock and setState per block, entities placed with DEFAULT_ENTITY_CONSUMER (a no-op lambda,
 *   which placeNoReturn passes too) and the same chunk updates afterwards.
 * - addEmptyAtWorldPos(x, y, z) adds block 0 and fluid 0 at local (x - position.x, ...); a new
 *   BlockSelection has position and anchor (0, 0, 0), so placing it at (0, 0, 0) puts each of
 *   those blocks at exactly (x, y, z). The air fallback therefore also writes fluid 0 there,
 *   clearing any water in those blocks.
 * - Water is not part of the saved ground: the capture records only the blocks (and fluids the
 *   prefab itself carries, none), and a fully solid pen block clears the fluid in its spot without
 *   recording it, so water that sat under solid pen blocks does not come back.
 *
 * The store is synchronized, so saves, deletes and moves of the one file never interleave.
 */
public final class PenTerrainRestorer implements PenTerrain {

    private final PenSnapshotStore store;
    private final Logger logger;

    public PenTerrainRestorer(PenSnapshotStore store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    @Override
    public PenPlacer.Placement place(World world, Vector3i feet, SproutwatchConfig config) throws IOException {
        return PenPlacer.place(world, feet, world.getWorldConfig().getUuid(), config, logger);
    }

    @Override
    public PenSnapshot load() throws IOException {
        return store.load().orElse(null);
    }

    @Override
    public boolean remember(PenSnapshot snapshot) {
        try {
            store.save(snapshot);
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not save the ground under the pen to " + store.file(), exception);
            return false;
        }
    }

    @Override
    public void forget() {
        try {
            store.delete();
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not delete " + store.file(), exception);
        }
    }

    @Override
    public void forgetIfStill(PenSnapshot snapshot) {
        try {
            store.deleteIfStill(snapshot);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not delete " + store.file(), exception);
        }
    }

    @Override
    public void setAside() {
        try {
            logger.warning("Sproutwatch: moved the unreadable saved pen ground to " + store.setAside());
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not move " + store.file() + " aside", exception);
        }
    }

    @Override
    public Restoration restore(World world, PenSite site, PenSnapshot snapshot) throws IOException {
        String worldUuid = world.getWorldConfig().getUuid().toString();
        if (!worldUuid.equals(site.worldUuid())) {
            throw new IllegalStateException("Pen ground for world " + site.worldUuid() + " asked to restore in world " + worldUuid);
        }
        if (snapshot != null && snapshot.matches(site)) {
            BlockSelection ground = SelectionPrefabSerializer.deserialize(snapshot.selection());
            // World block = local + position + paste position - anchor (same formula as the capture).
            Vector3i position = new Vector3i(
                snapshot.originX() - ground.getX() + ground.getAnchorX(),
                snapshot.originY() - ground.getY() + ground.getAnchorY(),
                snapshot.originZ() - ground.getZ() + ground.getAnchorZ());
            ground.place(null, world, position, null);
            logger.info("Sproutwatch: restored " + ground.getBlockCount() + " block(s) of ground under the pen at "
                + site.interiorX() + "," + site.floorY() + "," + site.interiorZ());
            return Restoration.RESTORED;
        }
        if (snapshot != null) {
            logger.warning("Sproutwatch: the saved pen ground is for another pen (world " + snapshot.worldUuid() + " at "
                + snapshot.interiorX() + "," + snapshot.floorY() + "," + snapshot.interiorZ() + "); setting the pen blocks to air instead");
        }
        clearToAir(world, site);
        return Restoration.CLEARED;
    }

    /**
     * Pens placed before saved ground existed: sets every block of the configured prefab to air at
     * the positions the placement used. Recomputed from the recorded interior corner, so this assumes
     * the prefab selection has not changed since that pen was placed (another prefab, or an edited
     * bundled prefab, would clear the wrong blocks).
     */
    private void clearToAir(World world, PenSite site) throws IOException {
        String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(site.prefabName()));
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        PenLayout layout = PenLayout.analyze(blocks, PenPlacer::isSeatBlock);
        // PenPlacer recorded interior = layout interior + origin (prefab-local equals selection-local).
        int originX = site.interiorX() - layout.interiorMinX();
        int originY = site.floorY() - layout.floorY();
        int originZ = site.interiorZ() - layout.interiorMinZ();
        BlockSelection air = new BlockSelection();
        for (PrefabBlock block : blocks) {
            air.addEmptyAtWorldPos(block.x() + originX, block.y() + originY, block.z() + originZ);
        }
        air.place(null, world, new Vector3i(0, 0, 0), null);
        logger.info("Sproutwatch: no saved ground for the pen at " + site.interiorX() + "," + site.floorY() + "," + site.interiorZ()
            + "; set its " + blocks.size() + " prefab block(s) to air");
    }
}
