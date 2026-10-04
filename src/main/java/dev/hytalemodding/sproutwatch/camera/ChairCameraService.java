package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.builtin.mounts.BlockMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.protocol.BlockMountType;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3i;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the fixed pen camera to a player who sits or lies on any seat or bed at the pen
 * (PenBounds.inGuardZone: interior plus 4 blocks) and resets it when they get up. ECS RefChangeSystem on the engine's MountedComponent (builtin/mounts): the
 * exact shape vanilla's MountSystems$PlayerMount and beds' WakeUpOnDismountSystem use (R2).
 * Callbacks run inside the store's write lock: reads and packet sends only, no store mutation.
 * Also backs /sproutwatch camera (manual toggle for tuning).
 */
public final class ChairCameraService extends RefChangeSystem<EntityStore, MountedComponent> {

    private final Supplier<SproutwatchConfig> config;
    private final Logger logger;
    private final Set<UUID> seated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> manual = ConcurrentHashMap.newKeySet();
    /** HUD components each seated player had visible before we hid the bottom UI; restored on stand.
     *  A game-mode change while seated is last-writer-wins (engine behavior); the pre-seat set is restored regardless. */
    private final Map<UUID, Set<HudComponent>> hudBeforeSeat = new ConcurrentHashMap<>();

    /** The bottom-of-screen UI hidden while seated on the pen chair (chat and notifications stay). */
    static final Set<HudComponent> HIDE_WHILE_SEATED = EnumSet.of(
        HudComponent.Hotbar, HudComponent.UtilitySlotSelector, HudComponent.Health, HudComponent.Stamina,
        HudComponent.Mana, HudComponent.Oxygen, HudComponent.Abilities, HudComponent.AmmoIndicator,
        HudComponent.StatusIcons, HudComponent.Reticle, HudComponent.InputBindings, HudComponent.Compass);

    public ChairCameraService(Supplier<SproutwatchConfig> config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public ComponentType<EntityStore, MountedComponent> componentType() {
        return MountedComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return MountedComponent.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, MountedComponent mounted, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (!isPenMountType(mounted.getBlockMountType())) return;
            SproutwatchConfig currentConfig = config.get();
            if (!currentConfig.isPenSet()) return;
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            if (!currentConfig.getPenWorld().equals(String.valueOf(player.getWorldUuid()))) return;
            if (!isPenSeat(currentConfig, seatBlock(mounted))) return;
            seated.add(player.getUuid());
            player.getPacketHandler().writeNoCache(CameraPackets.penCamera(currentConfig));
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform != null) SeatLook.apply(player, new org.joml.Vector3d(transform.getPosition()), PenBounds.fromConfig(currentConfig));
            hideBottomUi(ref, store, player);
            player.sendMessage(Message.raw("Sproutwatch camera on. Stand up to reset."));
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch chair camera failed on mount", exception);
        }
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, MountedComponent oldValue, MountedComponent newValue,
                               Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        // A seat swap is a remove + add from our point of view; nothing to do here.
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, MountedComponent mounted, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        try {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            if (seated.remove(player.getUuid())) {
                manual.remove(player.getUuid());
                player.getPacketHandler().writeNoCache(CameraPackets.reset());
                restoreBottomUi(ref, store, player);
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch chair camera failed on dismount", exception);
        }
    }

    /**
     * A seat counts as the pen chair when its block center is in the pen's guard zone (interior +
     * PenBounds.GUARD_MARGIN, floor up to clearHeight + 2): any chair, sofa or bed a player sets
     * next to the pen works, not just the one recorded at /sproutwatch place. Pure; tested.
     */
    static boolean isPenSeat(SproutwatchConfig config, Vector3i seat) {
        if (seat == null || !config.isPenSet()) return false;
        return PenBounds.fromConfig(config).inGuardZone(seat.x + 0.5, seat.y, seat.z + 0.5);
    }

    /** Every block mount counts: Seat (chairs, stools, benches, sofas, couches) and Bed. Creature mounts have none. */
    static boolean isPenMountType(BlockMountType type) {
        return type == BlockMountType.Seat || type == BlockMountType.Bed;
    }

    /** The seat's block position, read from the BlockMountComponent the rider is attached to. */
    private static Vector3i seatBlock(MountedComponent mounted) {
        Ref<ChunkStore> blockRef = mounted.getMountedToBlock();
        if (blockRef == null || !blockRef.isValid()) return null;
        BlockMountComponent bm = blockRef.getStore().getComponent(blockRef, BlockMountComponent.getComponentType());
        return bm == null ? null : bm.getBlockPos();
    }

    /** True while the player sits on the pen chair (not for the manual camera toggle). Any thread. */
    public boolean isSeated(UUID player) {
        return player != null && seated.contains(player);
    }

    /** /sproutwatch camera: toggles the fixed camera for a player without sitting. @return true if now on. */
    public boolean toggleManual(PlayerRef player) {
        UUID id = player.getUuid();
        if (manual.remove(id)) {
            try {
                player.getPacketHandler().writeNoCache(CameraPackets.reset());
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Sproutwatch manual camera failed", exception);
            }
            return false;
        }
        manual.add(id);
        try {
            player.getPacketHandler().writeNoCache(CameraPackets.penCamera(config.get()));
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch manual camera failed", exception);
        }
        return true;
    }

    /** Player left the server: drop any camera state so a later reconnect starts clean (disconnect never fires onComponentRemoved). */
    public void forget(UUID id) {
        seated.remove(id);
        manual.remove(id);
        hudBeforeSeat.remove(id);
    }

    /** Hides the bottom UI for the seated player, remembering what was visible (same call vanilla game modes use). */
    private void hideBottomUi(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        try {
            Player playerComponent = store.getComponent(ref, Player.getComponentType());
            if (playerComponent == null) return;
            HudManager hud = playerComponent.getHudManager();
            // EnumSet.noneOf + addAll: EnumSet.copyOf throws on an EMPTY non-EnumSet collection, and the
            // manager exposes an unmodifiable ConcurrentHashMap key set that a game mode can leave empty.
            Set<HudComponent> before = EnumSet.noneOf(HudComponent.class);
            before.addAll(hud.getVisibleHudComponents());
            hudBeforeSeat.put(player.getUuid(), before);
            hud.hideHudComponents(player, HIDE_WHILE_SEATED.toArray(new HudComponent[0]));
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not hide the HUD on sit", exception);
        }
    }

    /** Restores exactly what was visible before sitting; falls back to the engine defaults. */
    private void restoreBottomUi(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        try {
            Player playerComponent = store.getComponent(ref, Player.getComponentType());
            if (playerComponent == null) return;
            HudManager hud = playerComponent.getHudManager();
            Set<HudComponent> before = hudBeforeSeat.remove(player.getUuid());
            if (before != null) hud.setVisibleHudComponents(player, before);
            else hud.resetVisibleHudComponents(player);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Sproutwatch: could not restore the HUD on stand", exception);
        }
    }

    /** Re-sends the camera to everyone currently using it (after /sproutwatch interval-style tuning edits). */
    public void refresh(Collection<PlayerRef> players) {
        SproutwatchConfig currentConfig = config.get();
        for (PlayerRef p : players) {
            if (seated.contains(p.getUuid()) || manual.contains(p.getUuid())) {
                try {
                    p.getPacketHandler().writeNoCache(CameraPackets.penCamera(currentConfig));
                } catch (Exception exception) {
                    logger.log(Level.WARNING, "Sproutwatch camera refresh failed", exception);
                }
            }
        }
    }
}
