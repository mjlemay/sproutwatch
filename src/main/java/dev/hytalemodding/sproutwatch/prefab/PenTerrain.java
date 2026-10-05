package dev.hytalemodding.sproutwatch.prefab;

import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3i;

import java.io.IOException;

/**
 * The blocks of the pen: pasting the prefab, saving the ground the paste replaced and putting it
 * back when the pen is removed or moved. The seam between the pen actions and the engine, so the
 * actions can be unit tested with a fake; {@link PenTerrainRestorer} is the engine implementation.
 */
public interface PenTerrain {

    /** What {@link #restore} did. */
    enum Restoration {
        /** The saved ground was pasted back. */
        RESTORED,
        /** No saved ground for that pen (placed before this update): the pen's blocks were set to air. */
        CLEARED
    }

    /**
     * Pastes the configured prefab centered on feet and records the pen in config
     * ({@link PenPlacer#place}). Must run on that world's thread.
     * @throws IOException if the bundled prefab is missing
     */
    PenPlacer.Placement place(World world, Vector3i feet, SproutwatchConfig config) throws IOException;

    /**
     * The saved ground, or null when there is no saved-ground file. Any thread.
     * @throws IOException when the file exists but cannot be read or parsed
     */
    PenSnapshot load() throws IOException;

    /** Replaces the saved ground (one pen at a time). @return false when it could not be written (logged) */
    boolean remember(PenSnapshot snapshot);

    /** Deletes the saved ground; failures are logged. */
    void forget();

    /** Deletes the saved ground only while it still is that snapshot (a newer pen may have saved its own); failures are logged. */
    void forgetIfStill(PenSnapshot snapshot);

    /** Moves an unreadable saved-ground file aside (pen-restore.json.bad); failures are logged. */
    void setAside();

    /**
     * Puts the ground back at that site: pastes the snapshot when it was saved for that site,
     * otherwise sets the site's prefab blocks to air. Must run on the site world's thread.
     * @param snapshot the saved ground ({@link #load}), or null when there is no saved-ground file
     * @throws IOException when the air fallback cannot read the bundled prefab
     * @throws IllegalStateException when world is not the site's world
     */
    Restoration restore(World world, PenSite site, PenSnapshot snapshot) throws IOException;
}
