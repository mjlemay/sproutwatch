package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reacts to a sprout's death. The rule: a sprout killed by anything that is not a player (a
 * monster, fall, void...) is forgotten at once so its viewer respawns on the next tick; a sprout
 * killed by a player retires its viewer, who gets no sprout again while they stay in chat
 * (PenRegistry.retainRetired un-retires them when they drop out of the roster; /sproutwatch clear
 * also resets). Either way the registry entry is removed here, on death, which frees the cap slot
 * immediately instead of waiting for the corpse to be removed (PenDespawnSystem then finds nothing
 * for this ref, which is fine).
 *
 * An OnDeathSystem with a match-everything query
 * (the registry decides membership), ordered after the engine's ClearEntityEffects. The
 * killer check is the one vanilla DeathSystems.PlayerKilledPlayer does: the death's Damage source
 * is an EntitySource whose ref carries a PlayerRef component. Runs inside the store's write lock:
 * only reads (store.getComponent) and registry calls happen here, never a store mutation.
 */
public final class SproutDeathSystem extends DeathSystems.OnDeathSystem {

    private final PenRegistry registry;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    public SproutDeathSystem(PenRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, DeathSystems.ClearEntityEffects.class));
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent component,
                                 Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        if (registry.isEmpty()) return; // no sprouts live: every death is someone else's
        try {
            PenRegistry.Entry entry = registry.removeByReference(ref); // frees the slot now
            if (entry == null) return; // not a sprout
            PlayerRef attacker = null;
            Damage deathInfo = component.getDeathInfo();
            if (deathInfo != null && deathInfo.getSource() instanceof Damage.EntitySource entitySource) {
                Ref<EntityStore> sourceRef = entitySource.getRef();
                if (sourceRef != null && sourceRef.isValid()) {
                    attacker = store.getComponent(sourceRef, PlayerRef.getComponentType());
                }
            }
            if (attacker != null) {
                registry.retire(entry.viewerKey());
                logger.info("Sproutwatch: " + entry.viewerKey() + "'s sprout was killed by " + attacker.getUsername()
                    + "; retired until they leave chat");
            } else {
                logger.info("Sproutwatch: " + entry.viewerKey() + "'s sprout died (" + String.valueOf(component.getDeathCause())
                    + "); respawning next tick");
            }
        } catch (Exception exception) {
            if (warned.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Sproutwatch death handling failed", exception);
            }
        }
    }
}
