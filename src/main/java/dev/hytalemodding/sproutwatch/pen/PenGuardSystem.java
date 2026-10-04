package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps mobs off the pen and pen creatures off players (see {@link #decide}). When an NPC (not a sprout) hits a player or a viewer sprout that is at
 * the pen (PenBounds.inGuardZone: interior plus a few blocks, which covers the chair), the hit is
 * cancelled and the attacker is removed. Arrows count: Damage$ProjectileSource extends
 * EntitySource and getRef() is the shooter. Everywhere else combat is untouched. A
 * DamageEventSystem in DamageModule's filter group, like the engine's spawn protection; removal
 * goes through the CommandBuffer because this runs inside the store's write lock.
 */
public final class PenGuardSystem extends DamageEventSystem {

    private final Supplier<SproutwatchConfig> config;
    private final PenRegistry registry;
    private final Logger logger;

    public PenGuardSystem(Supplier<SproutwatchConfig> config, PenRegistry registry, Logger logger) {
        this.config = config;
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty(); // players and sprouts; decided per event below
    }

    enum Verdict { NONE, CANCEL, CANCEL_AND_REMOVE }

    /**
     * Pure decision. A pen creature (sprout) never damages a player, anywhere, and stays in the pen.
     * Any other NPC that hits a player or a sprout standing at the pen loses the hit and is removed.
     * Everything else (PvP, players hitting sprouts, sprouts among themselves, combat elsewhere) is untouched.
     */
    static Verdict decide(boolean attackerIsNpc, boolean attackerIsSprout, boolean victimIsPlayer,
                          boolean victimIsSprout, boolean victimAtPen) {
        if (!attackerIsNpc) return Verdict.NONE;
        if (attackerIsSprout) return victimIsPlayer ? Verdict.CANCEL : Verdict.NONE;
        if ((victimIsPlayer || victimIsSprout) && victimAtPen) return Verdict.CANCEL_AND_REMOVE;
        return Verdict.NONE;
    }

    /** The victim stands in the pen's guard zone, in the pen world. */
    private boolean atPen(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store) {
        SproutwatchConfig cfg = config.get();
        if (!cfg.isPenSet()) return false;
        if (!cfg.getPenWorld().equals(String.valueOf(store.getExternalData().getWorld().getWorldConfig().getUuid()))) return false;
        TransformComponent t = chunk.getComponent(index, TransformComponent.getComponentType());
        if (t == null) return false;
        Vector3d p = t.getPosition();
        return PenBounds.fromConfig(cfg).inGuardZone(p.x, p.y, p.z);
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled() || !(damage.getSource() instanceof Damage.EntitySource src)) return;
            Ref<EntityStore> attacker = src.getRef();
            if (attacker == null || !attacker.isValid()) return;
            boolean attackerIsNpc = commandBuffer.getComponent(attacker, NPCEntity.getComponentType()) != null;
            boolean attackerIsSprout = registry.findByRef(attacker) != null;
            boolean victimIsPlayer = chunk.getComponent(index, PlayerRef.getComponentType()) != null;
            boolean victimIsSprout = registry.findByRef(chunk.getReferenceTo(index)) != null;
            Verdict verdict = decide(attackerIsNpc, attackerIsSprout, victimIsPlayer, victimIsSprout, atPen(index, chunk, store));
            if (verdict == Verdict.NONE) return;

            damage.setCancelled(true);
            if (verdict == Verdict.CANCEL_AND_REMOVE) {
                commandBuffer.removeEntity(attacker, RemoveReason.REMOVE);
                NPCEntity npc = commandBuffer.getComponent(attacker, NPCEntity.getComponentType());
                logger.info("Sproutwatch: removed " + (npc == null ? "a mob" : npc.getRoleName()) + " that attacked "
                    + (victimIsPlayer ? "a player" : "a sprout") + " at the pen");
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Sproutwatch pen guard failed", e);
        }
    }
}
