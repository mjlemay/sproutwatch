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
import dev.hytalemodding.sproutwatch.prefab.PenPlacer;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;
import dev.hytalemodding.sproutwatch.prefab.PenSite;
import dev.hytalemodding.sproutwatch.prefab.PenSnapshot;
import dev.hytalemodding.sproutwatch.prefab.PenTerrain;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The pen and its sprouts, each returning the reply text: place, remove, clear, prefab, creatures,
 * max sprouts, tick interval, persist and test viewer removal. Work that must run on a world thread is
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

    /** Finds the sender's feet and places the pen there (see {@link #placeAt}). Runs on the world thread. */
    private void placeOnWorldThread(PlayerRef sender, World world) {
        try {
            Ref<EntityStore> reference = sender.getReference();
            if (reference == null || !reference.isValid()) return;
            Store<EntityStore> store = reference.getStore();
            TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
            if (transform == null) return;
            Vector3d position = transform.getPosition();
            Vector3i feet = new Vector3i((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
            sender.sendMessage(Message.raw(placeAt(world, feet)));
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
     * Takes away the old pen (sprouts and ground), pastes the prefab centered on feet, saves the
     * ground it replaced and the config. Runs on the world thread.
     * @return the reply for the sender
     * @throws IOException if the bundled prefab is missing (the old pen is already taken away and forgotten)
     */
    String placeAt(World world, Vector3i feet) throws IOException {
        return placeAt(world, feet, null);
    }

    /**
     * @param prefab the prefab to switch to once the old pen is taken away (under its own prefab name),
     *               or null to keep the configured one
     */
    private String placeAt(World world, Vector3i feet, String prefab) throws IOException {
        OldPen oldPen = takeAwayOldPen(world);
        if (!oldPen.proceed()) return oldPen.message();
        if (prefab != null) host.config().setPenPrefab(prefab);
        PenPlacer.Placement placement = host.penTerrain().place(world, feet, host.config());
        String saved = placement.ground() != null && host.penTerrain().remember(placement.ground())
            ? ""
            : " The ground under the pen could not be saved, so Remove pen will leave air there (see server log).";
        host.saveConfig();
        return placement.message() + oldPen.message() + saved;
    }

    /**
     * What happened to the old pen before a placement.
     * @param proceed false when the move must stop (the old pen is still placed)
     * @param message a suffix for the placement reply, or the whole reply when the move stops
     */
    private record OldPen(boolean proceed, String message) {}

    /**
     * Re-placing the pen: remove the previous pen's sprouts first (registry + any youngling of a
     * configured role inside the old bounds) so they do not linger untracked, and put the old spot's
     * ground back (saved ground, or air for a pen placed before saved ground existed). Runs on the
     * new pen's world thread; an old pen in another loaded world is handled on that world's thread
     * with the old site and ground captured now.
     *
     * Once the old pen is taken away (ground restored, queued for restoring, or its blocks left in
     * place) the pen is forgotten in config and saved at once, so a placement that fails afterwards
     * can never leave the config pointing at ground that was already restored (a later Remove would
     * otherwise dig air into it). The saved ground is deleted only after a successful restore; when
     * the restore fails, or the saved-ground file cannot be read, the move stops and the old pen and
     * its file are kept.
     */
    private OldPen takeAwayOldPen(World newWorld) {
        SproutwatchConfig config = host.config();
        if (!config.isPenSet()) return new OldPen(true, "");
        PenBounds oldBounds = PenBounds.fromConfig(config);
        PenSite oldSite = PenSite.of(config);
        PenTerrain terrain = host.penTerrain();
        PenSnapshot oldGround;
        try {
            oldGround = terrain.load();
        } catch (IOException exception) {
            host.logger().log(Level.WARNING, "Sproutwatch: the saved ground of the old pen could not be read", exception);
            terrain.setAside();
            return new OldPen(false, "Pen move stopped: the old pen's saved ground file could not be read (see server log); "
                + "the old pen is still placed. Place again to clear its blocks to air instead.");
        }
        World oldWorld = host.penWorld();
        String outcome;
        if (oldWorld == newWorld) {
            int cleared = host.sweepPen(newWorld, oldBounds);
            PenTerrain.Restoration restoration;
            try {
                restoration = terrain.restore(newWorld, oldSite, oldGround);
            } catch (Exception exception) {
                host.logger().log(Level.WARNING, "Sproutwatch: restoring the old pen's ground failed", exception);
                return new OldPen(false, "Pen move stopped: restoring the old pen's ground failed (see server log); "
                    + "the old pen is still placed and its saved ground is kept.");
            }
            terrain.forget();
            outcome = " Cleared " + cleared + " sprout(s) from the old pen" + switch (restoration) {
                case RESTORED -> " and restored the ground.";
                case CLEARED -> " and cleared the pen blocks (no saved ground from before this update).";
            };
        } else if (oldWorld != null) {
            boolean queued = host.runOnWorld(oldWorld, () -> {
                try {
                    host.sweepPen(oldWorld, oldBounds);
                    terrain.restore(oldWorld, oldSite, oldGround);
                    // The new pen may already have saved its own ground over the file; keep that.
                    terrain.forgetIfStill(oldGround);
                } catch (Exception exception) {
                    host.logger().log(Level.WARNING, "Sproutwatch: restoring the old pen's ground in its own world failed; its blocks stay", exception);
                }
            });
            outcome = queued
                ? " Clearing the old pen and restoring its ground in its own world."
                : " The old pen's world is unloading; its blocks stay where they are.";
        } else {
            int forgotten = host.registry().clear().size();
            outcome = " Old pen world not loaded; forgot " + forgotten + " tracked sprout(s); its blocks stay where they are.";
        }
        config.clearPen();
        host.saveConfig();
        return new OldPen(true, outcome);
    }

    /**
     * Remove pen: queues the removal on the pen world thread and answers at once; the outcome reaches
     * the sender as a chat message (or the server log when sender is null, from the console). Nothing
     * changes when the pen world is not loaded.
     */
    public String removePen(PlayerRef sender) {
        return removePen(message -> {
            if (sender != null) sender.sendMessage(Message.raw(message));
            else host.logger().info("Sproutwatch: " + message);
        });
    }

    /**
     * The listener is stopped right here, once the removal is queued, exactly like the Stop button:
     * on the page the caller is usually the pen world's thread (the streamer stands at the pen), and
     * stopping blocks that thread while the chat sources shut down (up to about a second each); from
     * a command it is the command thread. A tick that still reaches the world thread after the
     * removal finds no pen and does nothing.
     * @param reply receives the outcome on the pen world thread
     */
    String removePen(Consumer<String> reply) {
        if (!host.config().isPenSet()) return "No pen placed; nothing to remove.";
        PenSite queuedSite = PenSite.of(host.config());
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> removeOnWorldThread(world, queuedSite, reply));
        return switch (queued) {
            case QUEUED -> host.stopListener()
                ? "Removing the pen... (listener stopped)"
                : "Removing the pen...";
            case NOT_LOADED -> "Pen world not loaded; the pen was not removed. Go to the pen's world and try again.";
            case REJECTED -> "Pen world is unloading; try again.";
        };
    }

    /**
     * Clears the sprouts, restores the ground, forgets the pen and saves, when the configured pen is
     * still the one the removal was queued for. Runs on the pen world thread.
     */
    private void removeOnWorldThread(World world, PenSite queuedSite, Consumer<String> reply) {
        try {
            SproutwatchConfig config = host.config();
            if (!config.isPenSet() || !PenSite.of(config).sameSpot(queuedSite)) {
                reply.accept("The pen changed before it could be removed; nothing was removed.");
                return;
            }
            PenTerrain terrain = host.penTerrain();
            PenSnapshot ground;
            try {
                ground = terrain.load();
            } catch (IOException exception) {
                host.logger().log(Level.WARNING, "Sproutwatch: the saved pen ground could not be read", exception);
                terrain.setAside();
                reply.accept("Pen removal stopped: the saved ground file could not be read (see server log); the pen is still placed.");
                return;
            }
            int cleared = host.sweepPen(world, PenBounds.fromConfig(config));
            PenTerrain.Restoration restoration = terrain.restore(world, queuedSite, ground);
            terrain.forget();
            config.clearPen();
            host.saveConfig();
            reply.accept(switch (restoration) {
                case RESTORED -> "Pen removed: cleared " + cleared + " sprout(s) and restored the ground.";
                case CLEARED -> "Pen removed: cleared " + cleared + " sprout(s) and cleared the pen blocks (no saved ground from before this update).";
            });
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch remove pen failed", exception);
            reply.accept("Pen removal failed: " + exception.getMessage() + " (see server log); the pen is still placed.");
        }
        // Outside the removal try, like place: the page flips Remove pen back to Place pen here.
        try {
            host.statusChanged();
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", exception);
        }
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

    /**
     * Prefab dropdown: with no pen placed, just remembers the choice for the next placement. With a pen
     * placed, swaps it for the chosen prefab centred on the same spot, queued on the pen world thread;
     * the outcome reaches the sender as a chat message (or the server log when sender is null).
     */
    public String selectPrefab(PlayerRef sender, String raw) {
        return selectPrefab(raw, message -> {
            if (sender != null) sender.sendMessage(Message.raw(message));
            else host.logger().info("Sproutwatch: " + message);
        });
    }

    /** @param reply receives the swap outcome on the pen world thread (unused when no pen is placed) */
    String selectPrefab(String raw, Consumer<String> reply) {
        String name = PenPrefabCatalog.normalize(raw);
        if (!PenPrefabCatalog.contains(name)) {
            return "Unknown pen prefab '" + (raw == null ? "" : raw) + "'. Available: "
                + String.join(", ", PenPrefabCatalog.names()) + ".";
        }
        SproutwatchConfig config = host.config();
        String label = PenPrefabCatalog.labelFor(name);
        if (!config.isPenSet()) {
            config.setPenPrefab(name);
            host.saveConfig();
            return "Pen prefab set to " + label + ". Place the pen to paste it.";
        }
        if (name.equals(config.getPenPrefab())) return label + " is already the placed pen.";
        PenSite queuedSite = PenSite.of(config);
        ActionsHost.WorldQueue queued = host.runOnPenWorld(world -> swapOnWorldThread(world, queuedSite, name, reply));
        return switch (queued) {
            case QUEUED -> "Swapping the pen to " + label + "...";
            case NOT_LOADED -> "Pen world not loaded; the pen was not swapped. Go to the pen's world and try again.";
            case REJECTED -> "Pen world is unloading; try again.";
        };
    }

    /**
     * Re-places the pen with prefab, centred where the old pen's interior was centred, when the
     * configured pen is still the one the swap was queued for. Runs on the pen world thread.
     */
    private void swapOnWorldThread(World world, PenSite queuedSite, String prefab, Consumer<String> reply) {
        try {
            SproutwatchConfig config = host.config();
            if (!config.isPenSet() || !PenSite.of(config).sameSpot(queuedSite)) {
                reply.accept("The pen changed before it could be swapped; nothing was swapped.");
                return;
            }
            reply.accept(placeAt(world, centerFeet(config), prefab));
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch pen swap failed", exception);
            reply.accept("Pen swap failed: " + exception.getMessage() + " (see server log)");
        }
        // Outside the swap try, like place: the page shows the new pen here.
        try {
            host.statusChanged();
        } catch (Exception exception) {
            host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", exception);
        }
    }

    /** The feet position PenPlacer centres a pen on, recovered from the placed pen's interior. */
    static Vector3i centerFeet(SproutwatchConfig config) {
        return new Vector3i(config.getPenX() + config.getPenSizeX() / 2, config.getPenY() + 1,
            config.getPenZ() + config.getPenSizeZ() / 2);
    }
}
