package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every TickSeconds: resolve the pen world, hop onto its thread, mark every pen entry whose
 * viewer key is still in the roster as seen now, reconcile, despawn the stale (or, with PersistSprouts,
 * the longest-gone sprout when the pen is full), spawn one newcomer.
 * Owns a daemon scheduler and marshals onto the world via world.execute (a world.scheduleAfter
 * chain would die silently on world unload; this keeps polling and recovers). Every tick body is
 * caught and logged; the schedule never stops on an exception.
 */
public final class PenTicker {

    private final Supplier<SproutwatchConfig> config;
    private final ChatRoster roster;
    private final PenRegistry registry;
    private final SproutSpawner spawner;
    private final Logger logger;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sproutwatch-tick");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> future;
    private volatile Consumer<World> onWorldReady;
    private volatile Runnable afterTick;
    private final AtomicBoolean worldReadyFired = new AtomicBoolean();

    public PenTicker(Supplier<SproutwatchConfig> config, ChatRoster roster, PenRegistry registry,
                     SproutSpawner spawner, Logger logger) {
        this.config = config;
        this.roster = roster;
        this.registry = registry;
        this.spawner = spawner;
        this.logger = logger;
    }

    /** Runs once, on the world thread, the first time the pen world resolves (plugin boot sweep). */
    public void setOnWorldReady(Consumer<World> hook) {
        this.onWorldReady = hook;
    }

    /** Runs on the pen world thread at the end of every tick (settings pages push their status). */
    public void setAfterTick(Runnable hook) {
        this.afterTick = hook;
    }

    /** (Re)arms the schedule with the current TickSeconds. Idempotent. */
    public synchronized void start() {
        if (scheduler.isShutdown()) return;
        stop();
        int tickSeconds = config.get().getTickSeconds();
        future = scheduler.scheduleAtFixedRate(this::dispatch, tickSeconds, tickSeconds, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public synchronized boolean isRunning() {
        return future != null && !future.isDone();
    }

    /** Plugin shutdown: releases the scheduler thread. */
    public synchronized void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * Runs one tick as soon as the pen world thread gets to it (/sproutwatch test <login> now) and
     * hands {@code then} what it did, on that thread. Never called back when the pen world is not
     * loaded (callers check {@link #resolveWorld} first).
     */
    public void tickNow(Consumer<TickOutcome> then) {
        dispatch(then);
    }

    private void dispatch() {
        dispatch(null);
    }

    private void dispatch(Consumer<TickOutcome> then) {
        try {
            World world = resolveWorld(config.get());
            if (world == null) {
                logger.fine("Sproutwatch tick skipped: pen world not loaded");
                return;
            }
            Consumer<World> hook = onWorldReady;
            if (hook != null && worldReadyFired.compareAndSet(false, true)) {
                world.execute(() -> hook.accept(world));
            }
            world.execute(() -> {
                TickOutcome outcome = tickOnWorldThread(world);
                if (then != null) then.accept(outcome);
            });
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Sproutwatch tick dispatch failed", throwable);
        }
    }

    /** Null when no pen is placed, the UUID is malformed, or the world is not loaded. */
    public static World resolveWorld(SproutwatchConfig config) {
        String id = config.getPenWorld();
        if (id.isEmpty()) return null;
        try {
            Universe universe = Universe.get();
            if (universe == null) return null;
            return universe.getWorld(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    TickOutcome tickOnWorldThread(World world) {
        TickOutcome outcome = new TickOutcome(Optional.empty(), false);
        try {
            SproutwatchConfig currentConfig = config.get();
            long now = System.currentTimeMillis();

            dropPhantomEntries(world.getWorldConfig().getUuid());
            Map<String, Long> live = roster.snapshot();
            forgetDepartedViewers(live);
            Map<String, Long> eligible = eligibleViewers(live, currentConfig);
            // Presence is refreshed only for ELIGIBLE viewer keys: a viewer who was ignored or dropped off the
            // allow list while in chat stops being touched, so their sprout ages out after GraceSeconds.
            // Invariant: the same `now` passed to touch must be passed to reconcile; its strict `<` cutoff relies on it for grace 0.
            for (String viewerKey : eligible.keySet()) registry.touch(viewerKey, now);
            boolean persist = currentConfig.isPersistSprouts();
            PenPlan plan = reconcile(eligible, currentConfig, persist, now);
            applyDespawns(plan, persist);
            boolean spawned = spawnNext(world, plan);
            outcome = new TickOutcome(plan.spawn(), spawned);
            runAfterTick();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch tick failed", exception);
        }
        return outcome;
    }

    /**
     * Drops entries whose entity is already gone (missed PenDespawnSystem eviction, e.g. plugin
     * reload) or that live in a different world (pen re-placed in another world) so a phantom
     * sprout can never hold a cap slot forever. Re-placing via /sproutwatch place sweeps the old pen
     * itself; here only the registry entry is dropped.
     */
    private void dropPhantomEntries(UUID penWorldUuid) {
        for (PenRegistry.Entry e : registry.snapshot()) {
            Ref<EntityStore> ref = e.ref();
            if (ref == null || !ref.isValid() || (e.worldUuid() != null && !e.worldUuid().equals(penWorldUuid))) {
                registry.remove(e.viewerKey());
                logger.fine("Sproutwatch: dropping sprout entry for " + e.viewerKey() + " (entity gone or in another world)");
            }
        }
    }

    /** Forgets per-viewer state that lasts only while the viewer stays in chat. */
    private void forgetDepartedViewers(Map<String, Long> live) {
        // Retirement (a player killed their sprout) lasts only while the viewer stays in chat.
        registry.retainRetired(live.keySet());
        // Likewise the "!sprout" queue: a viewer who left chat leaves the queue (Part already
        // drops them, but NAMES-only presence and a missed PART are covered by this pass).
        roster.queue().retain(live.keySet());
    }

    /** The viewers in chat who may hold a sprout, with their first-seen times. */
    private Map<String, Long> eligibleViewers(Map<String, Long> live, SproutwatchConfig currentConfig) {
        // Retired viewers (a player killed their sprout; entry already dropped on death) are not eligible to respawn.
        Map<String, Long> eligible = new HashMap<>(live);
        eligible.keySet().removeAll(registry.retiredViewers());
        // Viewer filter: in allow mode only AllowUsers may get a sprout. The ignore list has already
        // been applied by ChatRoster (ignored viewer keys never enter the roster), so it always wins.
        if (currentConfig.isAllowMode()) eligible.keySet().retainAll(currentConfig.allowedViewers());
        return eligible;
    }

    /**
     * The queue is a priority over first-seen order: the first queued viewer key that is eligible
     * and not yet in the pen spawns next; otherwise the earliest-seen eligible viewer does.
     * PersistSprouts: grace is ignored; at the cap the sprout whose viewer has been gone the
     * longest (smallest untouched lastSeen) is replaced by the candidate, one swap per tick.
     */
    private PenPlan reconcile(Map<String, Long> eligible, SproutwatchConfig currentConfig, boolean persist, long now) {
        return PenReconciler.reconcile(
            ReconcileRequest.builder(eligible, registry.lastSeenMap(), currentConfig.getMaxSprouts(), now)
                .graceMillis(currentConfig.getGraceSeconds() * 1000L)
                .queue(roster.queue().snapshot())
                .persist(persist)
                .guests(roster.guests())
                .quiet(roster.lastActiveMap(), currentConfig.getQuietSeconds() * 1000L)
                .build());
    }

    private void applyDespawns(PenPlan plan, boolean persist) {
        for (String viewerKey : plan.despawn()) {
            PenRegistry.Entry entry = registry.remove(viewerKey);
            if (entry == null) continue;
            Ref<EntityStore> ref = entry.ref();
            if (ref != null && ref.isValid()) {
                try {
                    ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
                } catch (Exception exception) {
                    logger.log(Level.WARNING, "Sproutwatch: failed to despawn sprout for " + viewerKey, exception);
                    continue;
                }
            }
            logger.info(persist
                ? "Sproutwatch: " + viewerKey + " replaced to make room (viewer gone or no longer eligible)"
                : "Sproutwatch: " + viewerKey + " no longer eligible (left chat, ignored, or not allowed); sprout despawned");
        }
    }

    /**
     * A queue entry is consumed only by a successful spawn, so a failed attempt (no free
     * spot, role missing) keeps the viewer at the front for the next tick.
     */
    private boolean spawnNext(World world, PenPlan plan) {
        boolean spawned = false;
        if (plan.spawn().isPresent()) {
            String viewerKey = plan.spawn().get();
            spawned = spawner.spawn(world, viewerKey);
            if (spawned) roster.queue().remove(viewerKey);
        }
        return spawned;
    }

    private void runAfterTick() {
        Runnable after = afterTick;
        if (after != null) {
            try {
                after.run();
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch after-tick hook failed", exception);
            }
        }
    }
}
