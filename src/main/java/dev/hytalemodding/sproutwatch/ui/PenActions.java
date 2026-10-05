package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.CreaturePreset;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenReconciler;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.prefab.PenPlacer;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The pen and its sprouts, each returning the reply text: place, clear, prefab, creatures, max
 * sprouts, tick interval, persist and test viewer removal. Work that must run on a world thread is
 * queued through the host and answered with an interim message; the final result reaches the
 * player as a chat message from the world thread.
 */
public final class PenActions {

    private final ActionsHost host;

    PenActions(ActionsHost host) {
        this.host = host;
    }

    /**
     * Queues the pen placement on the sender's world thread and answers at once; the outcome reaches
     * the sender as a chat message from {@link #placeOnWorldThread}. Runs on the caller's thread.
     * @param sender the player to center the pen on; must be non-null in production (the fake host ignores it)
     */
    public String place(PlayerRef sender) {
        ActionsHost.WorldQueue queued = host.runOnPlayerWorld(sender, world -> placeOnWorldThread(sender, world));
        return switch (queued) {
            case QUEUED -> "Placing the pen...";
            case NOT_LOADED -> "Your world is not loaded.";
            case REJECTED -> "Your world is unloading; try again.";
        };
    }

    /** Sweeps the old pen, pastes the prefab centered on the sender and saves the config. Runs on the world thread. */
    private void placeOnWorldThread(PlayerRef sender, World world) {
        try {
            Ref<EntityStore> reference = sender.getReference();
            if (reference == null || !reference.isValid()) return;
            Store<EntityStore> store = reference.getStore();
            TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
            if (transform == null) return;
            Vector3d position = transform.getPosition();
            Vector3i feet = new Vector3i((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
            String swept = sweepOldPen(world);
            String message = PenPlacer.place(world, store, feet, world.getWorldConfig().getUuid(), host.config(), host.logger());
            host.saveConfig();
            sender.sendMessage(Message.raw(message + swept));
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch place failed", exception);
            sender.sendMessage(Message.raw("Pen placement failed: " + exception.getMessage() + " (see server log)"));
        }
        // No tick may be running; push the new pen to open settings pages now. Kept outside the
        // placement try so a refresh failure can never be reported as a failed placement.
        try {
            host.statusChanged();
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", exception);
        }
    }

    /**
     * Re-placing the pen: remove the previous pen's sprouts first (registry + any youngling of a
     * configured role inside the old bounds) so they do not linger untracked. Runs on the new
     * pen's world thread; an old pen in another loaded world is swept on that world's thread.
     * @return a suffix for the placement reply
     */
    private String sweepOldPen(World newWorld) {
        SproutwatchConfig config = host.config();
        if (!config.isPenSet()) return "";
        PenBounds oldBounds = PenBounds.fromConfig(config);
        World oldWorld = host.penWorld();
        if (oldWorld == newWorld) {
            int cleared = PenClearer.clear(newWorld, host.registry(), host.roleSet(), oldBounds, host.logger());
            return " Cleared " + cleared + " sprout(s) from the old pen.";
        }
        if (oldWorld != null) {
            PenRegistry registry = host.registry();
            Set<String> roles = host.roleSet();
            Logger logger = host.logger();
            host.runOnWorld(oldWorld, () -> PenClearer.clear(oldWorld, registry, roles, oldBounds, logger));
            return " Clearing the old pen in its own world.";
        }
        int forgotten = host.registry().clear().size();
        return " Old pen world not loaded; forgot " + forgotten + " tracked sprout(s).";
    }

    /** Drops a /sproutwatch test viewer and its sprout at once. */
    public String removeGuest(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (!host.roster().removeGuest(login)) return login + " is not a test viewer (/sproutwatch test list).";
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> {
            try {
                PenClearer.despawn(world, host.registry(), List.of(login), host.logger());
                host.statusChanged();
            } catch (Exception exception) {
                host.logger().log(Level.WARNING, "Sproutwatch guest despawn failed", exception);
            }
        });
        return switch (queued) {
            case QUEUED -> login + " removed from the test viewers; despawning their sprout...";
            case NOT_LOADED -> {
                host.registry().remove(login);
                yield login + " removed from the test viewers (pen world not loaded).";
            }
            case REJECTED -> login + " removed from the test viewers; the sprout goes on the next tick.";
        };
    }

    public String clear() {
        host.roster().forgetGuests(); // test viewers would otherwise respawn first (firstSeen 0)
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> {
            try {
                PenClearer.clear(world, host.registry(), host.roleSet(), PenBounds.fromConfig(host.config()), host.logger());
                host.statusChanged(); // no tick may be running; push the emptied pen to open settings pages now
            } catch (Exception exception) {
                host.logger().log(Level.WARNING, "Sproutwatch clear failed", exception);
            }
        });
        return switch (queued) {
            case QUEUED -> "Clearing the pen...";
            case NOT_LOADED -> "Pen world not loaded; forgot " + host.registry().clear().size() + " tracked sprout(s).";
            case REJECTED -> "Pen world is unloading; try again.";
        };
    }

    public String setPersist(boolean on) {
        host.config().setPersistSprouts(on);
        host.saveConfig();
        return on
            ? "Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced."
            : "Persist is now off: sprouts despawn " + host.config().getGraceSeconds()
                + "s after their viewer leaves (any already past that window go on the next tick).";
    }

    /** Pen tab Creatures dropdown: save, and clear the pen so it refills with the new kind (guests kept). */
    public String setCreatures(String id) {
        CreaturePreset next = CreaturePreset.parse(id);
        if (next.id().equals(host.config().getCreatures())) return "Creatures are already " + next.displayName() + ".";
        host.config().setCreatures(next.id());
        host.saveConfig();
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> {
            try {
                PenClearer.clear(world, host.registry(), host.roleSet(), PenBounds.fromConfig(host.config()), host.logger());
                host.statusChanged();
            } catch (Exception exception) {
                host.logger().log(Level.WARNING, "Sproutwatch creature switch clear failed", exception);
            }
        });
        String base = "Creatures: " + next.displayName() + ".";
        return switch (queued) {
            case QUEUED -> base + " Clearing the pen; it refills one per tick.";
            case NOT_LOADED -> base + " Pen world not loaded; forgot " + host.registry().clear().size() + " tracked sprout(s).";
            case REJECTED -> base + " Pen world is unloading; clear the pen when it is back.";
        };
    }

    /**
     * Saves the cap and, when the pen already holds more, trims the surplus at once (absent viewers'
     * sprouts first, then the newest arrivals) so the change is visible without a Clear.
     */
    public String setMaxSprouts(int max) {
        host.config().setMaxSprouts(max);
        host.saveConfig();
        int cap = host.config().getMaxSprouts();
        String base = "Max sprouts is now " + cap;
        List<String> extra = PenReconciler.trim(host.registry().lastSeenMap(), host.roster().snapshot(), cap, host.roster().guests());
        if (extra.isEmpty()) return base + ".";
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> {
            try {
                PenClearer.despawn(world, host.registry(), extra, host.logger());
                host.statusChanged();
            } catch (Exception exception) {
                host.logger().log(Level.WARNING, "Sproutwatch trim failed", exception);
            }
        });
        return switch (queued) {
            case QUEUED -> base + "; removing " + extra.size() + " extra sprout(s)...";
            case NOT_LOADED -> {
                for (String viewerKey : extra) host.registry().remove(viewerKey);
                yield base + "; pen world not loaded, forgot " + extra.size() + " tracked sprout(s).";
            }
            case REJECTED -> base + "; pen world is unloading, the extra sprouts go on the next tick.";
        };
    }

    public String setTickSeconds(int seconds) {
        host.config().setTickSeconds(seconds);
        host.saveConfig();
        if (host.tickerRunning()) host.restartTicker();
        return "Tick interval is now " + host.config().getTickSeconds() + "s.";
    }

    public String selectPrefab(String raw) {
        String name = PenPrefabCatalog.normalize(raw);
        if (!PenPrefabCatalog.contains(name)) {
            return "Unknown pen prefab '" + (raw == null ? "" : raw) + "'. Available: "
                + String.join(", ", PenPrefabCatalog.names()) + ".";
        }
        host.config().setPenPrefab(name);
        host.saveConfig();
        return "Pen prefab set to " + name + ". Place the pen to paste it.";
    }
}
