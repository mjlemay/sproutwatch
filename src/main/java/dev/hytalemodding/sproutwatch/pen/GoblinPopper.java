package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.CreaturePreset;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Goblins only: while the pen is at MaxSprouts, each tick has a 1 in 3 chance that one random goblin goes off like a failed
 * lab repair (the Lab Scrapper's Explosion_Small particles and Boomshroom sound) and is removed.
 * Goblins within BLAST_RADIUS are knocked away and up, harder the closer they stood. No damage,
 * no blocks broken. The viewer is not retired, so the freed slot refills on
 * a later tick (often with the same viewer in a fresh skin). World thread only.
 */
public final class GoblinPopper {

    static final String PARTICLES = "Explosion_Small";
    static final String SOUND = "SFX_Explosion_Boomshroom";
    /** A full pen pops a goblin on 1 tick in this many, on average. */
    static final int ONE_IN = 3;
    /** Blocks from the blast within which goblins get knocked back. */
    static final double BLAST_RADIUS = 4.0;
    /** Horizontal push at point blank, fading to nothing at BLAST_RADIUS. */
    static final double PUSH = 9.0;
    /** Upward hop at point blank, fading like PUSH. */
    static final double LIFT = 6.0;

    private final PenRegistry registry;
    private final Logger logger;
    private final Random random = new Random();

    public GoblinPopper(PenRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    /** Pure: the entry to pop, or empty when the preset is not goblins, the pen is under the cap, or the 1 in 3 roll misses. */
    static Optional<PenRegistry.Entry> pick(String creatures, int maxSprouts, List<PenRegistry.Entry> live, Random random) {
        if (!CreaturePreset.GOBLINS.id().equals(creatures) || live.isEmpty() || live.size() < maxSprouts) return Optional.empty();
        if (random.nextInt(ONE_IN) != 0) return Optional.empty();
        return Optional.of(live.get(random.nextInt(live.size())));
    }

    /**
     * Pure: the knockback for a goblin at `to` from a blast at `from`, or empty outside BLAST_RADIUS.
     * Away from the blast, scaled by closeness; a goblin right on top of it goes in a random direction.
     */
    static Optional<Vector3d> knockback(Vector3d from, Vector3d to, Random random) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(flat * flat + (to.y - from.y) * (to.y - from.y));
        if (distance > BLAST_RADIUS) return Optional.empty();
        if (flat < 1e-3) {
            double angle = random.nextDouble() * Math.PI * 2;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            flat = 1.0;
        }
        double strength = 1.0 - distance / BLAST_RADIUS;
        return Optional.of(new Vector3d(dx / flat * PUSH * strength, LIFT * strength, dz / flat * PUSH * strength));
    }

    /** @return true if a goblin was popped this tick. */
    public boolean popIfFull(World world, SproutwatchConfig currentConfig) {
        List<PenRegistry.Entry> live = registry.snapshot().stream()
            .filter(e -> e.ref() != null && e.ref().isValid())
            .toList();
        Optional<PenRegistry.Entry> victim = pick(currentConfig.getCreatures(), currentConfig.getMaxSprouts(), live, random);
        if (victim.isEmpty()) return false;
        PenRegistry.Entry entry = victim.get();
        Ref<EntityStore> ref = entry.ref();
        Store<EntityStore> store = world.getEntityStore().getStore();
        try {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            Vector3d blast = transform == null ? null : new Vector3d(transform.getPosition());
            registry.remove(entry.viewerKey());
            store.removeEntity(ref, RemoveReason.REMOVE);
            if (blast != null) {
                Vector3d at = new Vector3d(blast).add(0, 0.5, 0);
                ParticleUtil.spawnParticleEffect(PARTICLES, at, store);
                int sound = SoundEvent.getAssetMap().getIndex(SOUND);
                if (sound >= 0) SoundUtil.playSoundEvent3d(sound, SoundCategory.SFX, at, store);
                knockBackNeighbours(store, live, entry, blast);
            }
            logger.info("Sproutwatch: " + entry.viewerKey() + "'s goblin went boom (pen full)");
            return true;
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch: goblin pop failed for " + entry.viewerKey(), exception);
            return false;
        }
    }

    /** Pushes every other live goblin near the blast; one that cannot be pushed is skipped. */
    private void knockBackNeighbours(Store<EntityStore> store, List<PenRegistry.Entry> live, PenRegistry.Entry popped, Vector3d blast) {
        for (PenRegistry.Entry other : live) {
            if (other == popped || !other.ref().isValid()) continue;
            try {
                TransformComponent transform = store.getComponent(other.ref(), TransformComponent.getComponentType());
                if (transform == null) continue;
                Optional<Vector3d> push = knockback(blast, new Vector3d(transform.getPosition()), random);
                if (push.isEmpty()) continue;
                KnockbackComponent knockback = store.ensureAndGetComponent(other.ref(), KnockbackComponent.getComponentType());
                knockback.setVelocity(push.get());
                knockback.setVelocityType(ChangeVelocityType.Add);
                knockback.setDuration(0f);
                knockback.setTimer(0f);
            } catch (Exception exception) {
                logger.log(Level.FINE, "Sproutwatch: could not knock back " + other.viewerKey(), exception);
            }
        }
    }
}
