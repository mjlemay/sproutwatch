package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spawns one named youngling inside the pen. Same validated ladder as Subinator's BossSpawner
 * (up to 8 random validated spots, then a column probe over the pen centre); no unvalidated
 * fallback, a miss is retried next tick. Must run on the world thread.
 */
public final class SproutSpawner {

    private static final int PLACEMENT_ATTEMPTS = 8;

    private final Supplier<SproutwatchConfig> config;
    private final PenRegistry registry;
    private final DisplayNames displayNames;
    private final Logger logger;
    private final Random random = new Random();
    private final Set<String> warnedRoles = ConcurrentHashMap.newKeySet();

    public SproutSpawner(Supplier<SproutwatchConfig> config, PenRegistry registry, DisplayNames displayNames, Logger logger) {
        this.config = config;
        this.registry = registry;
        this.displayNames = displayNames;
        this.logger = logger;
    }

    /** Pure: a random pool entry; a "|" group entry then yields one of its roles at random. */
    static String pickRole(String[] pool, Random random) {
        String entry = pool[random.nextInt(pool.length)];
        if (entry.indexOf('|') < 0) return entry;
        String[] parts = java.util.Arrays.stream(entry.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return parts.length == 0 ? entry : parts[random.nextInt(parts.length)];
    }

    /** @return true if a sprout was spawned (and registered) for login. World thread only. */
    public boolean spawn(World world, String login) {
        try {
            SproutwatchConfig cfg = config.get();
            PenBounds bounds = PenBounds.fromConfig(cfg);
            if (!bounds.isSet()) {
                logger.warning("Sproutwatch: cannot spawn, pen not placed");
                return false;
            }
            String role = pickRole(cfg.spawnRoles(), random);
            Store<EntityStore> store = world.getEntityStore().getStore();
            UUID worldUuid = world.getWorldConfig().getUuid();
            NPCPlugin npcs = NPCPlugin.get();
            SpawnTestResult result = SpawnTestResult.FAIL_NO_POSITION;

            for (int i = 0; i < PLACEMENT_ATTEMPTS; i++) {
                Vector3d pos = bounds.randomPoint(random);
                Rotation3f rot = new Rotation3f(0f, (float) (random.nextDouble() * Math.PI * 2), 0f);
                result = npcs.spawnNPCWithSpaceValidation(
                    store, role, null, pos, rot,
                    (npc, npcRef, npcStore) -> onSpawned(npcRef, npcStore, login, role, worldUuid),
                    true, false);
                if (result == SpawnTestResult.TEST_OK) return true;
            }
            Vector3d c = bounds.center();
            result = npcs.spawnNPCWithColumnProbe(
                store, role, null, world, (int) Math.floor(c.x), (int) Math.floor(c.z), c.y + 1.0,
                new Rotation3f(0f, 0f, 0f),
                (npc, npcRef, npcStore) -> onSpawned(npcRef, npcStore, login, role, worldUuid));
            if (result == SpawnTestResult.TEST_OK) return true;

            if (isRoleProblem(result)) {
                if (warnedRoles.add(role)) {
                    logger.warning("Sproutwatch role '" + role + "' cannot spawn: " + result + " (check the Roles config)");
                }
            } else {
                logger.fine("Sproutwatch: no validated spot for " + login + " (" + result + "); retrying next tick");
            }
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch spawn failed for " + login, e);
            return false;
        }
    }

    static boolean isRoleProblem(SpawnTestResult r) {
        return r == SpawnTestResult.FAIL_NOT_SPAWNABLE
            || r == SpawnTestResult.FAIL_NO_MOTION_CONTROLLERS
            || r == SpawnTestResult.FAIL_NO_MOTION_CONTROLLER_MATCH
            || r == SpawnTestResult.FAIL_BREATHING_INCOMPATIBLE;
    }

    /**
     * Spawn init callback: world thread, outside the store's write-processing lock (Subinator's
     * TwitchAura mutates a plain Store from the same callback), so ensureAndGetComponent is safe.
     * Nameplate is what vanilla /entity nameplate uses (R3); it shows the display name (a YouTube
     * author's name), the key itself for Twitch.
     */
    private void onSpawned(Ref<EntityStore> npcRef, Store<EntityStore> npcStore, String login, String role, UUID worldUuid) {
        String name = displayNames.nameFor(login);
        try {
            npcStore.ensureAndGetComponent(npcRef, Nameplate.getComponentType()).setText(name);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch: could not set nameplate for " + login, e);
        }
        int networkId = -1;
        try {
            NetworkId nid = npcStore.getComponent(npcRef, NetworkId.getComponentType());
            if (nid != null) networkId = nid.getId();
        } catch (Exception e) {
            logger.log(Level.FINE, "Sproutwatch: no NetworkId for " + login, e);
        }
        long now = System.currentTimeMillis();
        registry.put(new PenRegistry.Entry(login, npcRef, networkId, worldUuid, now, now));
        // Log the key (unambiguous), plus the shown name when it differs.
        String shown = name.equals(login) ? "" : " (" + name + ")";
        logger.info("Sproutwatch: " + login + " joined the pen as " + role + shown);
    }
}
