package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Removes every tracked sprout plus any NPC whose role is in `roles` and whose position is
 * inside the pen (one block of margin). On boot, {@link #adopt} takes leftover creatures back
 * instead of wiping them: nothing about individual sprouts is persisted, so their nameplates are
 * the only link to their viewers. Must run on the world thread. Refs are collected first and removed
 * after the scan, because Store.removeEntity must not be called while iterating chunks.
 */
public final class PenClearer {

    private PenClearer() {}

    /** Removes the named sprouts (registry entry + entity). World thread only. @return how many entities were removed */
    public static int despawn(World world, PenRegistry registry, Collection<String> viewerKeys, Logger logger) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        int removed = 0;
        for (String viewerKey : viewerKeys) {
            PenRegistry.Entry entry = registry.remove(viewerKey);
            if (entry == null) continue;
            Ref<EntityStore> ref = entry.ref();
            if (ref == null || !ref.isValid()) continue;
            try {
                store.removeEntity(ref, RemoveReason.REMOVE);
                removed++;
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch: failed to despawn sprout for " + viewerKey, exception);
            }
        }
        return removed;
    }

    /**
     * Restart safety without the wipe: creatures saved in the pen from the last session are taken
     * back into the registry when they can be traced to a viewer (see {@link PenAdoption}); the rest
     * are removed. Tracked sprouts are left alone. World thread only.
     *
     * @param keepRoles roles the current creature set spawns
     * @param sweepRoles every role this mod has ever put in a pen (what counts as "ours")
     * @return how many were taken back
     */
    public static int adopt(World world, PenRegistry registry, Set<String> keepRoles, Set<String> sweepRoles,
                            PenBounds bounds, int maxSprouts, Logger logger) {
        if (!bounds.isSet()) return 0;
        Store<EntityStore> store = world.getEntityStore().getStore();
        UUID worldUuid = world.getWorldConfig().getUuid();
        List<PenAdoption.Found<Ref<EntityStore>>> found = new ArrayList<>();
        try {
            store.forEachChunk(Archetype.of(NPCEntity.getComponentType()), (chunk, cb) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                    if (npc == null || npc.getRoleName() == null || !sweepRoles.contains(npc.getRoleName())) continue;
                    TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (transform == null) continue;
                    Vector3d position = transform.getPosition();
                    if (!bounds.contains(position.x, position.y, position.z, 1.0)) continue;
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    if (registry.findByReference(ref) != null) continue;
                    Nameplate nameplate = chunk.getComponent(i, Nameplate.getComponentType());
                    found.add(new PenAdoption.Found<>(ref, npc.getRoleName(), nameplate == null ? null : nameplate.getText()));
                }
            });
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch pen scan failed; leftover creatures left as they are", exception);
            return 0;
        }
        Set<String> tracked = new HashSet<>();
        for (PenRegistry.Entry e : registry.snapshot()) tracked.add(e.viewerKey());
        PenAdoption.Plan<Ref<EntityStore>> plan = PenAdoption.plan(found, keepRoles, tracked, maxSprouts);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Ref<EntityStore>> keep : plan.adopt().entrySet()) {
            Ref<EntityStore> ref = keep.getValue();
            int networkId = -1;
            try {
                NetworkId nid = store.getComponent(ref, NetworkId.getComponentType());
                if (nid != null) networkId = nid.getId();
            } catch (Exception ignored) {
                // -1 is what the spawner records when an entity has no NetworkId
            }
            registry.put(new PenRegistry.Entry(keep.getKey(), ref, networkId, worldUuid, now, now));
        }
        int removed = 0;
        for (Ref<EntityStore> ref : plan.remove()) {
            try {
                if (ref.isValid()) {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch: failed to remove a leftover creature", exception);
            }
        }
        logger.info("Sproutwatch: kept " + plan.adopt().size() + " creature(s) from the last session, removed " + removed);
        return plan.adopt().size();
    }

    /** @return how many entities were removed */
    public static int clear(World world, PenRegistry registry, Set<String> roles, PenBounds bounds, Logger logger) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Set<Ref<EntityStore>> victims = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PenRegistry.Entry e : registry.clear()) {
            if (e.ref() != null && e.ref().isValid()) victims.add(e.ref());
        }
        if (bounds.isSet()) {
            try {
                store.forEachChunk(Archetype.of(NPCEntity.getComponentType()), (chunk, cb) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                        if (npc == null || npc.getRoleName() == null || !roles.contains(npc.getRoleName())) continue;
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        Vector3d position = transform.getPosition();
                        if (bounds.contains(position.x, position.y, position.z, 1.0)) victims.add(chunk.getReferenceTo(i));
                    }
                });
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch pen scan failed; removing tracked sprouts only", exception);
            }
        }
        int removed = 0;
        for (Ref<EntityStore> ref : new ArrayList<>(victims)) {
            try {
                if (ref.isValid()) {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch: failed to remove a sprout during clear", exception);
            }
        }
        logger.info("Sproutwatch: cleared " + removed + " sprout(s)");
        return removed;
    }
}
