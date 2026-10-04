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
import java.util.ArrayList;
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

    /** Selectors of the labels refreshStatus() pushes, in the order statusLabels() emits them: the Details cells, two captions, the Viewers tab's lookup line. */
    private static final List<String> STATUS_SELECTORS = statusSelectors();

    private static List<String> statusSelectors() {
        List<String> sel = new ArrayList<>();
        for (String id : StatusSnapshot.DETAIL_IDS) sel.add(id + ".Text");
        sel.add("#RunLabel.Text");
        sel.add("#BeginCaption.Text");
        sel.add("#LookupLabel.Text");
        return List.copyOf(sel);
    }

    private final SproutwatchActions actions;
    private final OpenPages openPages;
    private final Logger logger;
    // Only ever touched on the player's world thread (refreshStatus never reads them), but a player
    // can change worlds while the page is open, so successive events may arrive on different threads.
    private volatile SettingsTab tab = SettingsTab.CONNECT;
    private volatile String message = "";
    // Written by build() (player thread) and refreshStatus() (pen thread); the benign race costs one extra push.
    private volatile List<String> lastSentLabels = List.of();
    // listsKey() of the lists the last build() showed; a change triggers a rebuild so new rows appear.
    private volatile String lastSentLists = "";
    // A refresh queued on the pen thread can land after the player dismissed the page.
    private volatile boolean dismissed;

    public SproutwatchSettingsPage(PlayerRef playerRef, SproutwatchActions actions, OpenPages openPages, Logger logger) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SettingsEvent.CODEC);
        this.actions = actions;
        this.openPages = openPages;
        this.logger = logger;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder evt, @Nonnull Store<EntityStore> store) {
        StatusSnapshot s = actions.snapshot();
        SproutwatchConfig cfg = actions.config();
        cmd.append(DOCUMENT);
        SettingsTab.bind(evt);
        SettingsTab.apply(cmd, tab);
        List<String> labels = statusLabels(s, lookupLine());
        setStatusLabels(cmd, labels);
        lastSentLabels = labels;
        lastSentLists = listsKey(cfg);
        SettingsPanes.connect(cmd, evt, cfg, s);
        SettingsPanes.viewers(cmd, evt, cfg, s);
        SettingsPanes.listener(cmd, evt, s);
        SettingsPanes.pen(cmd, evt, s, cfg);
        cmd.set("#MessageLabel.Text", message);
        // Last, so a build that throws (e.g. a bad .ui) never leaves a phantom entry refreshed every tick.
        openPages.register(playerRef.getUuid(), this);
    }

    /**
     * The labels refreshStatus() pushes, in STATUS_SELECTORS order: the Details table, then two captions,
     * then the Viewers tab's YouTube lookup line (so a finished lookup shows without a click),
     * then (last three, not selectors) the Listener cell's style name, whether Wrangle is enabled
     * ("pen"/"nopen") and which run button shows ("start"/"connecting"/"stop"), so all of those
     * follow state changes live. Never a field value.
     */
    private static List<String> statusLabels(StatusSnapshot s, String lookupLine) {
        List<String> labels = new ArrayList<>(s.detailValues());
        labels.add(s.runLabel());
        labels.add(s.beginCaption());
        labels.add(lookupLine);
        labels.add(s.listenerTone());
        labels.add(s.penSet() ? "pen" : "nopen");
        labels.add(s.runButton());
        return List.copyOf(labels);
    }

    /** The last YouTube handle lookup (pending or its outcome) for the Viewers tab; "" when none. */
    private String lookupLine() {
        return actions.lastLookupMessage().orElse("");
    }

    private static void setStatusLabels(UICommandBuilder cmd, List<String> labels) {
        for (int i = 0; i < STATUS_SELECTORS.size(); i++) cmd.set(STATUS_SELECTORS.get(i), labels.get(i));
        int n = labels.size();
        cmd.set("#ListenerValue.Style", Value.<String>ref(DOCUMENT, labels.get(n - 3)));
        cmd.set("#BeginButton.Disabled", !labels.get(n - 2).equals("pen"));
        String run = labels.get(n - 1);
        cmd.set("#StartButton.Visible", run.equals("start"));
        cmd.set("#ConnectingButton.Visible", run.equals("connecting"));
        cmd.set("#StopButton.Visible", run.equals("stop"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull SettingsEvent e) {
        String action = e.action == null ? "" : e.action;
        if (action.equals("tab")) {
            switchTab(e.tab);
            return;
        }
        if (action.equals("begin") && begin()) return;
        try {
            message = switch (action) {
                case "begin" -> message; // begin() already set the error
                case "saveChannel" -> actions.setChannel(e.channel);
                case "setTwitchEnabled" -> actions.setTwitchEnabled(Boolean.TRUE.equals(e.twitchOn));
                case "setYouTubeEnabled" -> actions.setYouTubeEnabled(Boolean.TRUE.equals(e.youTubeOn));
                case "saveYouTubeHandle" -> actions.setYouTubeHandle(e.youTubeHandle);
                case "saveYouTubeKey" -> actions.setYouTubeKey(e.youTubeKey); // reply is masked; the field is rebuilt empty
                case "saveYouTubeVideo" -> actions.setYouTubeVideo(e.youTubeVideo); // blank clears
                case "removeChannel" -> actions.removeChannel();
                case "removeYouTubeHandle" -> actions.removeYouTubeHandle();
                case "removeYouTubeKey" -> actions.removeYouTubeKey();
                case "removeYouTubeVideo" -> actions.setYouTubeVideo("");
                case "saveMax" -> e.max == null ? "Enter a number of sprouts (min 1)." : actions.setMaxSprouts(e.max);
                case "setFilter" -> actions.setFilter("allow".equals(e.filter));
                case "addAllow" -> actions.addAllow(e.allowInput);
                case "removeAllow" -> actions.removeAllow(e.login);
                case "addIgnore" -> actions.addIgnore(e.ignoreInput);
                case "removeIgnore" -> actions.removeIgnore(e.login);
                case "startListener" -> actions.startListener();
                case "stopListener" -> actions.stopListener();
                case "setPersist" -> actions.setPersist(Boolean.TRUE.equals(e.persist));
                case "setAutoStart" -> actions.setAutoStart(Boolean.TRUE.equals(e.autoStart));
                case "saveInterval" -> e.interval == null ? "Enter a number of seconds (min 5)." : actions.setTickSeconds(e.interval);
                case "selectPrefab" -> actions.selectPrefab(e.prefab);
                case "selectCreatures" -> actions.setCreatures(e.creatures);
                case "place" -> actions.place(playerRef);
                case "clear" -> actions.clear();
                default -> "Unknown action: " + action;
            };
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page action '" + action + "' failed", ex);
            message = "Action failed: " + ex.getMessage() + " (see server log)";
        }
        try {
            rebuild();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page rebuild failed", ex);
        }
    }

    /** Begin: start the listener (unless running) and close the page. @return true when closed; false leaves the error for the rebuild. */
    private boolean begin() {
        try {
            Optional<String> err = actions.begin();
            if (err.isPresent()) {
                message = err.get();
                return false;
            }
            close(); // PageManager.setPage(None): fires onDismiss, so OpenPages forgets us
            return true;
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page begin failed", ex);
            message = "Action failed: " + ex.getMessage() + " (see server log)";
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
            UICommandBuilder cmd = new UICommandBuilder();
            SettingsTab.apply(cmd, tab);
            sendUpdate(cmd);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page tab switch failed", ex);
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
        if (dismissed) return false;
        Ref<EntityStore> ref = playerRef.getReference();
        if (!playerRef.isValid() || ref == null || !ref.isValid()) return false;
        try {
            List<String> labels = statusLabels(actions.snapshot(), lookupLine());
            String lists = listsKey(actions.config());
            if (labels.equals(lastSentLabels) && lists.equals(lastSentLists)) return true;
            Store<EntityStore> store = ref.getStore();
            Runnable push = () -> pushOnWorldThread(labels, lists);
            if (store.isInThread()) push.run();
            else store.getExternalData().getWorld().execute(push);
            return true;
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page refresh failed; dropping the page", ex);
            return false;
        }
    }

    /** Runs on the player's world thread. A failure here is logged; the page stays registered. */
    private void pushOnWorldThread(List<String> labels, String lists) {
        if (dismissed) return;
        try {
            if (!lists.equals(lastSentLists)) {
                rebuild();   // build() records labels + lists
                return;
            }
            UICommandBuilder cmd = new UICommandBuilder();
            setStatusLabels(cmd, labels);
            sendUpdate(cmd);
            lastSentLabels = labels;
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page push failed", ex);
        }
    }

    /** Fingerprint of what the Viewers lists show; kept out of statusLabels (setStatusLabels indexes its tail). */
    private static String listsKey(SproutwatchConfig c) {
        return c.allowedLogins() + "|" + c.ignoredLogins() + "|" + c.youTubeLabels();
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true; // before forget: a refresh already past refreshAll's iteration must still bail out
        openPages.forget(playerRef.getUuid(), this);
    }
}
