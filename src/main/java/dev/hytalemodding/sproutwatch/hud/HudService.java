package dev.hytalemodding.sproutwatch.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenTicker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps every online player's top-right panel in step with the config (HudEnabled, HudMessage) and
 * the pen ticker (the bar). Polls 20 times a second from its own daemon thread and hands each
 * world's players to that world's thread; turning the panel off removes it on the next poll.
 */
public final class HudService {

    /** 20 updates a second, so the bar creeps rather than jumps; unchanged bars send nothing. */
    private static final long PERIOD_MILLIS = 50;

    private final Supplier<SproutwatchConfig> config;
    private final PenTicker ticker;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sproutwatch-hud");
        thread.setDaemon(true);
        return thread;
    });

    public HudService(Supplier<SproutwatchConfig> config, PenTicker ticker, Logger logger) {
        this.config = config;
        this.ticker = ticker;
        this.logger = logger;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::poll, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void poll() {
        try {
            Universe universe = Universe.get();
            if (universe == null) return;
            SproutwatchConfig current = config.get();
            boolean enabled = current.isHudEnabled();
            String message = current.getHudMessage();
            float progress = HudLayout.progress(ticker.millisToNextTick(), current.getTickSeconds());
            for (World world : universe.getWorlds().values()) {
                if (world.getPlayerRefs().isEmpty()) continue;
                world.execute(() -> {
                    for (PlayerRef playerRef : world.getPlayerRefs()) apply(world, playerRef, enabled, message, progress);
                });
            }
        } catch (Throwable throwable) {
            warnOnce("Sproutwatch HUD poll failed", throwable);
        }
    }

    /** World thread: show, update or remove one player's panel. */
    private void apply(World world, PlayerRef playerRef, boolean enabled, String message, float progress) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) return;
            Store<EntityStore> store = world.getEntityStore().getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;
            HudManager huds = player.getHudManager();
            CustomUIHud existing = huds.getCustomHud(SproutwatchHud.KEY);
            if (!enabled) {
                if (existing != null) huds.removeCustomHud(playerRef, SproutwatchHud.KEY);
                return;
            }
            if (existing instanceof SproutwatchHud hud) {
                hud.refresh(message, progress);
            } else {
                huds.addCustomHud(playerRef, new SproutwatchHud(playerRef, message, progress));
            }
        } catch (Exception exception) {
            warnOnce("Sproutwatch HUD update failed for " + playerRef.getUsername(), exception);
        }
    }

    private void warnOnce(String what, Throwable throwable) {
        if (warned.compareAndSet(false, true)) logger.log(Level.WARNING, what + " (further HUD errors are not logged)", throwable);
    }
}
