package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

/**
 * Every mutation the settings page or a chat command can perform, grouped by job: chat sources and
 * the listener, the pen, and the viewer lists. Shared by SproutwatchCommand and
 * SproutwatchSettingsPage so both behave identically; also builds the status both of them show.
 */
public final class SproutwatchActions {

    private final ActionsHost host;
    private final ChatSourceActions chatSources;
    private final PenActions pen;
    private final ViewerListActions viewerLists;

    public SproutwatchActions(ActionsHost host) {
        this.host = host;
        this.chatSources = new ChatSourceActions(host);
        this.pen = new PenActions(host);
        this.viewerLists = new ViewerListActions(host);
    }

    /** Twitch and YouTube setup, start, stop, Begin and auto-start. */
    public ChatSourceActions chatSources() {
        return chatSources;
    }

    /** Place, clear, prefab, creatures, max sprouts, tick interval, persist and test viewers. */
    public PenActions pen() {
        return pen;
    }

    /** The filter mode and the allow / ignore lists, including YouTube handle lookups. */
    public ViewerListActions viewerLists() {
        return viewerLists;
    }

    public SproutwatchConfig config() {
        return host.config();
    }

    public StatusSnapshot snapshot() {
        SproutwatchConfig config = host.config();
        World world = config.isPenSet() ? host.penWorld() : null;
        return new StatusSnapshot(
            host.listenerState(), host.listenerRunning(), host.feedAcknowledged(),
            config.getTwitchChannel(), host.roster().size(), host.roster().queue().size(), config.getQueueCommand(),
            config.isAllowMode(), config.allowedViewers().size(), config.ignoredViewers().size(),
            host.registry().size(), config.getMaxSprouts(), host.registry().retiredViewers().size(),
            config.getTickSeconds(), config.getGraceSeconds(), config.getQuietSeconds(), host.tickerRunning(),
            config.isPersistSprouts(), config.isAutoStartOnBoot(),
            config.isPenSet(), config.getPenX(), config.getPenY(), config.getPenZ(), config.getPenSizeX(), config.getPenSizeZ(), config.getPenFacing(),
            world == null ? null : world.getName(), config.getPenWorld(),
            config.isChairSet(), config.getChairX(), config.getChairY(), config.getChairZ(),
            config.getPenPrefab(),
            host.sourceStates(), host.youTubeStatus());
    }

    /** The /sproutwatch status text. */
    public String statusReport() {
        return snapshot().report();
    }
}
