package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Evicts a registry entry when its entity is removed for any reason (killed, chunk unload,
 * /kill, shutdown, or our own despawn). The next tick then respawns that viewer if they are
 * still in chat. No component marks a sprout, so the query matches every entity and the
 * registry decides membership; the isEmpty() check keeps chunk-unload storms cheap. Fires
 * inside the store's write lock: no store mutation here.
 */
public final class PenDespawnSystem extends RefSystem<EntityStore> {

    private final PenRegistry registry;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    public PenDespawnSystem(PenRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer) {
        // SproutSpawner registers from its spawn callback.
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        if (registry.isEmpty()) return; // chunk-unload storms with no sprouts live
        try {
            PenRegistry.Entry entry = registry.removeByReference(ref);
            if (entry != null) logger.info("Sproutwatch: sprout for " + entry.viewerKey() + " removed (" + reason + ")");
        } catch (Exception exception) {
            if (warned.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Sproutwatch registry eviction failed on entity removal", exception);
            }
        }
    }
}
