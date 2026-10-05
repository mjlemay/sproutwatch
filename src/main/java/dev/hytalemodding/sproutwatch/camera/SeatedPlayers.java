package dev.hytalemodding.sproutwatch.camera;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The players currently seated on a pen seat. ChairCameraService sets it on mount and dismount;
 * SeatedInvulnerabilitySystem reads it. The manual /sproutwatch camera toggle never adds to it,
 * so it grants no invulnerability. Safe to call from any thread.
 */
public final class SeatedPlayers {

    private final Set<UUID> seated = ConcurrentHashMap.newKeySet();

    /** @return true if the player was not already seated. */
    public boolean add(UUID player) {
        return seated.add(player);
    }

    /** @return true if the player was seated. */
    public boolean remove(UUID player) {
        return seated.remove(player);
    }

    /** True while the player sits on the pen seat. A null player is never seated. */
    public boolean isSeated(UUID player) {
        return player != null && seated.contains(player);
    }

    /** Player left the server: drop them without caring whether they were seated. */
    public void forget(UUID player) {
        seated.remove(player);
    }
}
