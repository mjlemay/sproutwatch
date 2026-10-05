package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The /sproutwatch settings page: Connect, Details, Viewers, Listener and Pen tabs (see SettingsTab;
 * panes filled by SettingsPanes), every button routed through SproutwatchActions, result shown on
 * the message line, page rebuilt after each action except a tab switch (partial update, so typed
 * text survives it) and Begin (closes the page). Registers itself in OpenPages on build so
 * PenTicker's after-tick hook can push the Details table live via refreshStatus(); removed on
 * dismiss (here) and disconnect (plugin). The push is change-gated: every sendUpdate bumps the
 * engine's per-player unacknowledged-update counter and client data events (button clicks) are
 * dropped while it is non-zero, so an unconditional push every tick would silently eat clicks.
 */
public final class SproutwatchSettingsPage extends InteractiveCustomUIPage<SettingsEvent> implements OpenPages.Page {

    static final String DOCUMENT = "Pages/Sproutwatch/SettingsPage.ui";

    private final SproutwatchActions actions;
    private final OpenPages openPages;
    private final Logger logger;
    // Only ever touched on the player's world thread (refreshStatus never reads them), but a player
    // can change worlds while the page is open, so successive events may arrive on different threads.
    private volatile SettingsTab tab = SettingsTab.CONNECT;
    private volatile String message = "";
    // Owns what the last build() or push showed, the dismissed flag and the world-thread handoff.
    private final StatusPusher statusPusher;

    public SproutwatchSettingsPage(PlayerRef playerRef, SproutwatchActions actions, OpenPages openPages, Logger logger) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SettingsEvent.CODEC);
        this.actions = actions;
        this.openPages = openPages;
        this.logger = logger;
        this.statusPusher = new StatusPusher(new EngineSink(), logger);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        StatusSnapshot snapshot = actions.snapshot();
        SproutwatchConfig config = actions.config();
        commands.append(DOCUMENT);
        SettingsTab.bind(events);
        SettingsTab.apply(commands, tab);
        PageState state = PageState.of(snapshot, lookupLine(), listsKey(config));
        applyState(commands, state);
        statusPusher.recordBuilt(state);
        SettingsPanes.connect(commands, events, config, snapshot);
        SettingsPanes.viewers(commands, events, config, snapshot);
        SettingsPanes.listener(commands, events, snapshot);
        SettingsPanes.pen(commands, events, snapshot, config);
        commands.set("#MessageLabel.Text", message);
        // Last, so a build that throws (e.g. a bad .ui) never leaves a phantom entry refreshed every tick.
        openPages.register(playerRef.getUuid(), this);
    }

    /** The last YouTube handle lookup (pending or its outcome) for the Viewers tab; "" when none. */
    private String lookupLine() {
        return actions.viewerLists().lastLookupMessage().orElse("");
    }

    /**
     * Sets what refreshStatus() pushes: the labels (the Details table, two captions, and the Viewers
     * tab's YouTube lookup line, so a finished lookup shows without a click), then the Listener cell's
     * style, whether Wrangle is enabled, which run button shows and whether Place pen here or Remove
     * pen shows, so all of those follow state changes live. The lists fingerprint is not pushed.
     */
    private static void applyState(UICommandBuilder commands, PageState state) {
        List<String> labels = state.labels();
        for (int i = 0; i < PageState.SELECTORS.size(); i++) commands.set(PageState.SELECTORS.get(i), labels.get(i));
        commands.set("#ListenerValue.Style", Value.<String>ref(DOCUMENT, state.listenerTone()));
        commands.set("#BeginButton.Disabled", !state.penSet());
        // Place pen here and Remove pen share a spot: one shows, by whether a pen is placed.
        commands.set("#PlaceButton.Visible", !state.penSet());
        commands.set("#RemovePenButton.Visible", state.penSet());
        for (RunButton button : RunButton.values()) {
            commands.set(runButtonSelector(button) + ".Visible", button == state.runButton());
        }
    }

    /** The page element of each run button; no default, so a new RunButton fails to compile here. */
    private static String runButtonSelector(RunButton button) {
        return switch (button) {
            case START -> "#StartButton";
            case CONNECTING -> "#ConnectingButton";
            case STOP -> "#StopButton";
        };
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull SettingsEvent event) {
        String action = event.action == null ? "" : event.action;
        // null when the client sent a name no PageAction carries; the switch reports it.
        PageAction pageAction = PageAction.fromWire(action).orElse(null);
        if (pageAction == PageAction.TAB) {
            switchTab(event.tab);
            return;
        }
        if (pageAction == PageAction.BEGIN && begin()) return;
        try {
            // No default: a PageAction without a case here fails to compile.
            message = switch (pageAction) {
                case null -> "Unknown action: " + action;
                case TAB -> message; // handled above (partial update, no rebuild); never reached
                case BEGIN -> message; // begin() already set the error
                case SAVE_CHANNEL -> actions.chatSources().setChannel(event.channel);
                case SET_TWITCH_ENABLED -> actions.chatSources().setTwitchEnabled(Boolean.TRUE.equals(event.twitchOn));
                case SET_YOUTUBE_ENABLED -> actions.chatSources().setYouTubeEnabled(Boolean.TRUE.equals(event.youTubeOn));
                case SAVE_YOUTUBE_HANDLE -> actions.chatSources().setYouTubeHandle(event.youTubeHandle);
                case SAVE_YOUTUBE_KEY -> actions.chatSources().setYouTubeKey(event.youTubeKey); // reply is masked; the field is rebuilt empty
                case SAVE_YOUTUBE_VIDEO -> actions.chatSources().setYouTubeVideo(event.youTubeVideo); // blank clears
                case REMOVE_CHANNEL -> actions.chatSources().removeChannel();
                case REMOVE_YOUTUBE_HANDLE -> actions.chatSources().removeYouTubeHandle();
                case REMOVE_YOUTUBE_KEY -> actions.chatSources().removeYouTubeKey();
                case REMOVE_YOUTUBE_VIDEO -> actions.chatSources().setYouTubeVideo("");
                case SAVE_MAX -> event.max == null ? "Enter a number of sprouts (min 1)." : actions.pen().setMaxSprouts(event.max);
                case SET_FILTER -> actions.viewerLists().setFilter("allow".equals(event.filter));
                case ADD_ALLOW -> actions.viewerLists().addAllow(event.allowInput);
                case REMOVE_ALLOW -> actions.viewerLists().removeAllow(event.viewerKey);
                case ADD_IGNORE -> actions.viewerLists().addIgnore(event.ignoreInput);
                case REMOVE_IGNORE -> actions.viewerLists().removeIgnore(event.viewerKey);
                case START_LISTENER -> actions.chatSources().startListener();
                case STOP_LISTENER -> actions.chatSources().stopListener();
                case SET_PERSIST -> actions.pen().setPersist(Boolean.TRUE.equals(event.persist));
                case SET_AUTO_START -> actions.chatSources().setAutoStart(Boolean.TRUE.equals(event.autoStart));
                case SAVE_INTERVAL -> event.interval == null ? "Enter a number of seconds (min 5)." : actions.pen().setTickSeconds(event.interval);
                case SELECT_PREFAB -> actions.pen().selectPrefab(event.prefab);
                case SELECT_CREATURES -> actions.pen().setCreatures(event.creatures);
                case PLACE -> actions.pen().place(playerRef);
                case REMOVE_PEN -> actions.pen().removePen(playerRef);
                case CLEAR -> actions.pen().clear();
            };
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page action '" + action + "' failed", exception);
            message = "Action failed: " + exception.getMessage() + " (see server log)";
        }
        try {
            rebuild();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page rebuild failed", exception);
        }
    }

    /** Begin: start the listener (unless running) and close the page. @return true when closed; false leaves the error for the rebuild. */
    private boolean begin() {
        try {
            Optional<String> beginError = actions.chatSources().begin();
            if (beginError.isPresent()) {
                message = beginError.get();
                return false;
            }
            close(); // PageManager.setPage(None): fires onDismiss, so OpenPages forgets us
            return true;
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page begin failed", exception);
            message = "Action failed: " + exception.getMessage() + " (see server log)";
            return false;
        }
    }

    /**
     * A tab switch is a partial update (styles + visibility only): no rebuild, so typed text and the
     * message line survive it. Server state follows the click even if the push fails (the next
     * rebuild resends it). Always pushes, even for the already-active tab: the button's binding locks
     * the client UI until some update arrives.
     */
    private void switchTab(String name) {
        tab = SettingsTab.parse(name, tab);
        try {
            UICommandBuilder commands = new UICommandBuilder();
            SettingsTab.apply(commands, tab);
            sendUpdate(commands);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Sproutwatch settings page tab switch failed", exception);
        }
    }

    /**
     * Called from any thread: the pen world thread after a tick (possibly another world than the
     * player's), a chat source's thread on a state change, the YouTube lookup thread, or a world
     * thread after Place/Clear. Calls may overlap for the same page. sendUpdate/rebuild touch the
     * player's entity store, which asserts its own world thread, so this only computes what changed
     * here and hands the push to the player's world thread (directly when already on it). Pushes
     * only on change: each sendUpdate opens a window in which the engine drops the player's clicks
     * (see the class comment). A change to the allow/ignore lists (or their labels) rebuilds the
     * page so new rows appear; otherwise only the status labels are pushed.
     */
    @Override
    public boolean refreshStatus() {
        if (statusPusher.isDismissed()) return false;
        Ref<EntityStore> ref = playerRef.getReference();
        if (!playerRef.isValid() || ref == null || !ref.isValid()) return false;
        return statusPusher.refresh(
            () -> PageState.of(actions.snapshot(), lookupLine(), listsKey(actions.config())), new PlayerWorld(ref));
    }

    /** The player's world thread, reached through the entity store only when the push needs it. */
    private record PlayerWorld(Ref<EntityStore> ref) implements StatusPusher.WorldExecutor {
        @Override public boolean isInThread() {
            return ref.getStore().isInThread();
        }

        @Override public void execute(Runnable task) {
            ref.getStore().getExternalData().getWorld().execute(task);
        }
    }

    /** The engine calls StatusPusher makes on the player's world thread. */
    private final class EngineSink implements StatusPusher.PageSink {
        @Override public void rebuild() {
            SproutwatchSettingsPage.this.rebuild();
        }

        @Override public void sendState(PageState state) {
            UICommandBuilder commands = new UICommandBuilder();
            applyState(commands, state);
            sendUpdate(commands);
        }
    }

    /** Fingerprint of what the Viewers lists show (PageState.listsKey); never pushed as a label. */
    private static String listsKey(SproutwatchConfig config) {
        return config.allowedViewers() + "|" + config.ignoredViewers() + "|" + config.youTubeLabels();
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        statusPusher.dismiss(); // before forget: a refresh already past refreshAll's iteration must still bail out
        openPages.forget(playerRef.getUuid(), this);
    }
}
