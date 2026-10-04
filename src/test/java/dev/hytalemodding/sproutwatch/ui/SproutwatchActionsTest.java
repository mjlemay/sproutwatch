package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.twitch.SproutQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SproutwatchActionsTest {

    /** Records every side effect and never touches the engine: World is only a type here. */
    static final class FakeHost implements ActionsHost {
        final SproutwatchConfig cfg = new SproutwatchConfigAccess().fresh();
        final ChatRoster roster = new ChatRoster(cfg::ignoredLogins, cfg::getQueueCommand, new SproutQueue());
        final PenRegistry registry = new PenRegistry();
        final Logger logger = Logger.getLogger("SproutwatchActionsTest");
        boolean running;
        boolean acked;
        String startError;
        int startCalls, stopCalls, saveCalls, restartCalls;
        boolean tickerRunning;
        WorldQueue penQueue = WorldQueue.NOT_LOADED;
        WorldQueue playerQueue = WorldQueue.NOT_LOADED;
        final List<Consumer<World>> penTasks = new ArrayList<>();
        final List<Consumer<World>> playerTasks = new ArrayList<>();

        @Override public SproutwatchConfig config() { return cfg; }
        @Override public void saveConfig() { saveCalls++; }
        @Override public Logger logger() { return logger; }
        @Override public ChatRoster roster() { return roster; }
        @Override public PenRegistry registry() { return registry; }
        @Override public Set<String> roleSet() { return cfg.sweepRoles(); }
        /** Per-source states; null = derive them like the plugin does from config + running. */
        Map<String, String> states;
        /** Refuse a start the way the plugin does when no source can start (SproutwatchConfig.nothingToStartReason). */
        boolean refuseLikePlugin;
        /** YouTube is configured but its source fails to build, so it is absent after a start. */
        boolean youTubeFails;
        final java.time.Clock clock = java.time.Clock.fixed(
            java.time.LocalDateTime.of(2026, 10, 3, 12, 0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant(),
            java.time.ZoneId.of("America/Los_Angeles"));
        @Override public Map<String, String> sourceStates() {
            if (states != null) return states;
            boolean tw = cfg.twitchReady();
            boolean yt = cfg.youTubeConfigured();
            if (!running) return StatusSnapshot.sourceStates(null, tw, null, yt);
            return StatusSnapshot.sourceStates(
                tw ? "connected to #" + cfg.getTwitchChannel() : null, tw,
                yt && !youTubeFails ? "connected to YouTube (" + YouTubeStatus.target(cfg) + ")" : null, yt);
        }
        @Override public YouTubeStatus youTubeStatus() { return YouTubeStatus.of(cfg, null, clock); }
        @Override public String listenerState() { return StatusSnapshot.joinStates(sourceStates()); }
        @Override public boolean listenerRunning() { return running; }
        @Override public boolean feedAcked() { return acked; }
        /** Contract: a start always stops the current run first, refused or not. */
        @Override public String startListener() {
            startCalls++;
            running = false;
            String err = startError != null ? startError : refuseLikePlugin ? cfg.nothingToStartReason() : null;
            if (err != null) return err;
            running = true;
            return null;
        }
        @Override public boolean stopListener() {
            stopCalls++;
            boolean was = running;
            running = false;
            return was;
        }
        @Override public boolean tickerRunning() { return tickerRunning; }
        @Override public void restartTicker() { restartCalls++; }
        @Override public World penWorld() { return null; }
        @Override public WorldQueue runOnPenWorld(Consumer<World> task) {
            if (penQueue == WorldQueue.QUEUED) penTasks.add(task);
            return penQueue;
        }
        @Override public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
            if (playerQueue == WorldQueue.QUEUED) playerTasks.add(task);
            return playerQueue;
        }
        @Override public boolean runOnWorld(World world, Runnable task) { return false; }
        int changedCalls;
        @Override public void statusChanged() { changedCalls++; }
        /** Handles looked up, and the futures a test completes to script each outcome. */
        final List<String> lookups = new ArrayList<>();
        final List<java.util.concurrent.CompletableFuture<String>> lookupFutures = new ArrayList<>();
        @Override public java.util.concurrent.CompletableFuture<String> lookUpYouTubeChannel(String handle) {
            lookups.add(handle);
            java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
            lookupFutures.add(f);
            return f;
        }
    }

    @Test void setChannelWhenStoppedSavesAndTellsHowToStart() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Channel set to #streamer. Run /sproutwatch start to begin.", a.setChannel("#Streamer!"));
        assertEquals("streamer", h.cfg.getTwitchChannel());
        assertEquals(1, h.saveCalls);
        assertEquals(0, h.startCalls);
    }

    @Test void setChannelWhileRunningRestartsListener() {
        FakeHost h = new FakeHost();
        h.running = true;
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Channel set to #streamer; listener restarted.", a.setChannel("streamer"));
        assertEquals(1, h.startCalls);
        h.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(h.startError, a.setChannel("other"));
        assertEquals("other", h.cfg.getTwitchChannel(), "the channel is saved even when the restart is refused");
        assertEquals(2, h.saveCalls);
    }

    @Test void setChannelRejectsInvalidInput() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Invalid channel name.", a.setChannel("!!!"));
        assertEquals("Invalid channel name.", a.setChannel(null));
        assertEquals(0, h.saveCalls);
    }

    @Test void startListenerPassesRefusalThrough() {
        FakeHost h = new FakeHost();
        h.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals(h.startError, new SproutwatchActions(h).startListener());
    }

    @Test void startAndStopWording() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", a.startListener());
        assertTrue(h.running);
        assertEquals("Sproutwatch stopped.", a.stopListener());
        assertEquals("Sproutwatch was not running.", a.stopListener());
        assertEquals(2, h.stopCalls);
    }

    @Test void allowAddRemoveNormalisesAndSaves() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Added alice to the allow list.", a.addAllow("@Alice"));
        assertEquals("alice is already on the allow list.", a.addAllow("alice"));
        assertEquals(Set.of("alice"), h.cfg.allowedLogins());
        assertEquals("Removed alice from the allow list.", a.removeAllow("ALICE"));
        assertEquals("alice is not on the allow list.", a.removeAllow("alice"));
        assertEquals(2, h.saveCalls, "saved once per successful change");
    }

    @Test void ignoreAddPartsTheViewerAndRemoveRestores() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.roster.apply(new RosterEvent.Chat("spammer", "!sprout"), 1L);
        assertEquals(1, h.roster.size());
        assertEquals(1, h.roster.queue().size());
        assertEquals("Added spammer to the ignore list.", a.addIgnore("Spammer"));
        assertEquals(0, h.roster.size(), "an ignored viewer leaves the roster at once");
        assertEquals(0, h.roster.queue().size());
        assertTrue(h.cfg.ignoredLogins().contains("spammer"));
        assertEquals("spammer is already on the ignore list.", a.addIgnore("spammer"));
        assertEquals("Removed spammer from the ignore list.", a.removeIgnore("spammer"));
        assertEquals("spammer is not on the ignore list.", a.removeIgnore("spammer"));
        assertEquals(2, h.saveCalls);
    }

    @Test void invalidLoginsAreRejectedEverywhere() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        for (String bad : new String[]{null, "", "   ", "!!!"}) {
            assertEquals("Invalid login.", a.addAllow(bad));
            assertEquals("Invalid login.", a.removeAllow(bad));
            assertEquals("Invalid login.", a.addIgnore(bad));
            assertEquals("Invalid login.", a.removeIgnore(bad));
        }
        assertEquals(0, h.saveCalls);
    }

    @Test void persistSetSavesAndExplains() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Persist is now off: sprouts despawn 300s after their viewer leaves (any already past that window go on the next tick).", a.setPersist(false));
        assertFalse(h.cfg.isPersistSprouts());
        assertEquals("Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced.", a.setPersist(true));
        assertTrue(h.cfg.isPersistSprouts());
        assertEquals(2, h.saveCalls);
    }

    @Test void autoStartTogglesConfigAndSaves() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Auto-start on boot is now on: the listener reconnects when the world loads.", a.setAutoStart(true));
        assertTrue(h.cfg.isAutoStartOnBoot());
        assertEquals("Auto-start on boot is now off: press Start listener after each launch.", a.setAutoStart(false));
        assertFalse(h.cfg.isAutoStartOnBoot());
        assertEquals(2, h.saveCalls);
    }

    @Test void maxSproutsClampsToAtLeastOneAndSaves() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Max sprouts is now 12.", a.setMaxSprouts(12));
        assertEquals(12, h.cfg.getMaxSprouts());
        assertEquals("Max sprouts is now 1.", a.setMaxSprouts(0));
        assertEquals(1, h.cfg.getMaxSprouts());
        assertEquals(2, h.saveCalls);
    }

    @Test void removeGuestDropsTheRosterEntryAndDespawnsOnThePenWorld() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("alice is not a test viewer (/sproutwatch test list).", a.removeGuest("Alice"));
        h.roster.addGuest("alice", 0L);
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("alice removed from the test viewers; despawning their sprout...", a.removeGuest("alice"));
        assertEquals(Set.of(), h.roster.guests());
        assertEquals(1, h.penTasks.size());
        h.roster.addGuest("bob", 0L);
        h.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("bob removed from the test viewers (pen world not loaded).", a.removeGuest("bob"));
    }

    @Test void setFilterSwitchesModeAndSaves() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (the list is empty, so nobody until you add someone).",
            a.setFilter(true));
        assertTrue(h.cfg.isAllowMode());
        a.addAllow("alice");
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (1 listed).", a.setFilter(true));
        assertEquals("Filter is now the ignore list: everyone in chat except 3 ignored.", a.setFilter(false));
        assertFalse(h.cfg.isAllowMode());
        assertEquals(4, h.saveCalls, "three filter changes and one addAllow");
        a.setFilter(true);
        assertEquals("Filter: allow list (1 allowed)", a.snapshot().filterLine());
    }

    @Test void switchingCreaturesSavesAndClearsThePenButKeepsGuests() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.roster.addGuest("alice", 0L);
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Creatures: Pigs. Clearing the pen; it refills one per tick.", a.setCreatures("pigs"));
        assertEquals("pigs", h.cfg.getCreatures());
        assertEquals(1, h.saveCalls);
        assertEquals(1, h.penTasks.size(), "the clear is queued on the pen world");
        assertEquals(Set.of("alice"), h.roster.guests(), "test viewers keep their place");
        assertEquals("Creatures are already Pigs.", a.setCreatures("Pigs"));
        assertEquals(1, h.penTasks.size(), "no clear when nothing changed");
    }

    @Test void beginStartsTheListenerOrReportsWhyNot() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(Optional.of(h.startError), a.begin());
        h.startError = null;
        assertEquals(Optional.empty(), a.begin());
        assertEquals(2, h.startCalls, "the failed attempt and the successful one");
        assertTrue(h.running);
        assertEquals(Optional.empty(), a.begin(), "already running: nothing to start, just close");
        assertEquals(2, h.startCalls);
    }

    @Test void clearForgetsGuestsSoTheyDoNotRespawn() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.roster.addGuest("alice", 0L);
        h.roster.apply(new RosterEvent.Join("bob"), 5L);
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", a.clear());
        assertEquals(Set.of(), h.roster.guests());
        assertEquals(Map.of("bob", 5L), h.roster.snapshot());
    }

    @Test void loweringMaxBelowThePenCountTrimsTheSurplusOnThePenWorld() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.registry.put(new PenRegistry.Entry("a", null, -1, null, 0L, 10L));
        h.registry.put(new PenRegistry.Entry("b", null, -1, null, 0L, 20L));
        h.registry.put(new PenRegistry.Entry("c", null, -1, null, 0L, 30L));
        h.roster.apply(new RosterEvent.Chat("c", ""), 5L);
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Max sprouts is now 1; removing 2 extra sprout(s)...", a.setMaxSprouts(1));
        assertEquals(1, h.penTasks.size(), "the despawn is queued on the pen world thread");
        h.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        h.cfg.setMaxSprouts(3);
        assertEquals("Max sprouts is now 2; pen world not loaded, forgot 1 tracked sprout(s).", a.setMaxSprouts(2));
        assertEquals(2, h.registry.size());
    }

    @Test void tickSecondsClampsAndRestartsRunningTicker() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Tick interval is now 5s.", a.setTickSeconds(1));
        assertEquals(0, h.restartCalls, "a stopped ticker is not re-armed");
        h.tickerRunning = true;
        assertEquals("Tick interval is now 30s.", a.setTickSeconds(30));
        assertEquals(1, h.restartCalls);
        assertEquals(2, h.saveCalls);
    }

    @Test void selectPrefabRejectsUnknownAndSavesKnown() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Unknown pen prefab 'castle'. Available: default.", a.selectPrefab("castle"));
        assertEquals("Unknown pen prefab ''. Available: default.", a.selectPrefab(null));
        assertEquals(0, h.saveCalls);
        assertEquals("Pen prefab set to default. Place the pen to paste it.", a.selectPrefab(" Default "));
        assertEquals("default", h.cfg.getPenPrefab());
        assertEquals(1, h.saveCalls);
    }

    @Test void placeReportsQueueOutcome() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.playerQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Placing the pen...", a.place(null));
        assertEquals(1, h.playerTasks.size(), "the paste runs on the player's world thread");
        h.playerQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Your world is not loaded.", a.place(null));
        h.playerQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Your world is unloading; try again.", a.place(null));
        assertEquals(1, h.playerTasks.size());
    }

    @Test void clearReportsQueueOutcomeAndForgetsWhenUnloaded() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        h.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Pen world not loaded; forgot 1 tracked sprout(s).", a.clear());
        assertEquals(0, h.registry.size());
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", a.clear());
        assertEquals(1, h.penTasks.size());
        h.penQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Pen world is unloading; try again.", a.clear());
        assertEquals(1, h.penTasks.size());
    }

    @Test void snapshotReflectsHostAndConfig() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.cfg.setChair(true, 17, 65, 13);
        h.cfg.setPenFacing("south");
        h.cfg.addAllow("alice");
        h.cfg.addAllow("dave");
        h.cfg.addAllow("erin");
        h.roster.apply(new RosterEvent.Chat("bob", "!sprout"), 1L);
        h.roster.apply(new RosterEvent.Chat("frank", "!sprout"), 2L);
        h.roster.apply(new RosterEvent.Chat("grace", "hello"), 3L);
        h.roster.apply(new RosterEvent.Chat("heidi", "hello"), 4L);
        h.roster.apply(new RosterEvent.Chat("ivan", "hello"), 5L);
        h.registry.put(new PenRegistry.Entry("bob", null, -1, null, 0L, 0L));
        h.registry.put(new PenRegistry.Entry("frank", null, -1, null, 0L, 0L));
        h.registry.put(new PenRegistry.Entry("grace", null, -1, null, 0L, 0L));
        h.registry.put(new PenRegistry.Entry("heidi", null, -1, null, 0L, 0L));
        h.registry.retire("carol");
        h.running = true;
        h.acked = true;
        h.tickerRunning = true;
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("connected to #streamer", s.listenerState());
        assertTrue(s.listenerRunning());
        assertTrue(s.feedAcked());
        assertEquals("streamer", s.channel());
        // Every count is distinct so a positional swap in the 29-component constructor fails here.
        assertEquals(5, s.rosterSize());
        assertEquals(2, s.queueSize());
        assertEquals("!sprout", s.queueCommand());
        assertEquals(3, s.allowCount());
        assertEquals(4, s.penCount());
        assertEquals(30, s.cap());
        assertEquals(1, s.retiredCount());
        assertEquals(60, s.tickSeconds());
        assertEquals(300, s.graceSeconds());
        assertTrue(s.tickerRunning());
        assertTrue(s.persist());
        assertTrue(s.penSet());
        assertEquals(10, s.penX());
        assertEquals(64, s.penY());
        assertEquals(20, s.penZ());
        assertEquals(16, s.penSizeX());
        assertEquals(12, s.penSizeZ());
        assertEquals("south", s.penFacing());
        assertNull(s.penWorldName(), "the fake host never loads a world");
        assertEquals("11111111-2222-3333-4444-555555555555", s.penWorldId());
        assertTrue(s.chairSet());
        assertEquals(17, s.chairX());
        assertEquals(65, s.chairY());
        assertEquals(13, s.chairZ());
        assertEquals("default", s.prefabName());
    }

    @Test void statusReportMatchesCommandWording() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals(String.join("\n",
            "Listener: stopped",
            "Channel: (unset)",
            "Seen in chat: 0 (chatters since Start)",
            "Queue: 0 waiting (typed !sprout in chat)",
            "Filter: ignore list (everyone in chat except 3 ignored)",
            "Pen: 0/30 sprouts, tick 60s, grace 300s, ticker stopped",
            "Persist: on (longest-gone replaced at the cap)",
            "Pen: not placed (run /sproutwatch place)"), a.statusReport());

        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.running = true;
        h.registry.retire("carol");
        String running = a.statusReport();
        assertTrue(running.contains("Listener: connected to #streamer\n"), running);
        assertTrue(running.contains("Channel: #streamer\n"), running);
        assertTrue(running.contains("Twitch JOIN/PART feed: OFF (Twitch did not grant membership; only viewers who chat will appear)\n"), running);
        assertTrue(running.contains("Retired (killed by a player): 1\n"), running);
        assertTrue(running.contains("Pen placed: 16x12 at 10,64,20 in an unloaded world (11111111-2222-3333-4444-555555555555), camera faces north\n"), running);
        assertTrue(running.endsWith("Chair: (none; use /sproutwatch camera)"), running);
    }

    @Test void snapshotLabelsForThePage() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        StatusSnapshot stopped = a.snapshot();
        assertEquals("Twitch JOIN/PART feed: n/a (listener stopped)", stopped.feedLine());
        assertEquals("Listener stopped", stopped.runLabel());
        assertEquals("Place a pen to wrangle viewers", stopped.beginCaption());
        assertEquals("Persist on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced", stopped.persistLabel());
        assertEquals("Auto-start off: press Start listener after each launch", stopped.autoStartLabel());
        assertEquals(List.of("(unset)", "stopped", "n/a (listener stopped)", "YouTube off", "on (longest-gone replaced at the cap)",
            "0 (chatters since Start)", "0 waiting (typed !sprout in chat)", "ignore list (everyone in chat except 3 ignored)",
            "0/30 sprouts, tick 60s, grace 300s, ticker stopped", "0"), stopped.detailValues());
        assertEquals(StatusSnapshot.DETAIL_NAMES.size(), stopped.detailValues().size());
        assertEquals(StatusSnapshot.DETAIL_IDS.size(), stopped.detailValues().size());
        assertEquals("#ChannelValue", StatusSnapshot.DETAIL_IDS.get(0));
        assertEquals("#YouTubeQuotaValue", StatusSnapshot.DETAIL_IDS.get(3));
        assertEquals("#FilterValue", StatusSnapshot.DETAIL_IDS.get(7));
        assertEquals("ValuePlain", stopped.listenerTone());
        assertEquals("Chair: (no pen)", stopped.chairLine());
        assertEquals("Retired (killed by a player): 0", stopped.retiredLine());

        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPersistSprouts(false);
        h.cfg.setAutoStartOnBoot(true);
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.cfg.setChair(true, 17, 65, 13);
        h.running = true;
        h.acked = true;
        StatusSnapshot running = a.snapshot();
        assertEquals("Twitch JOIN/PART feed: on", running.feedLine());
        assertEquals("Listener running (connected to #streamer)", running.runLabel());
        assertEquals("Close and bring viewers to the pen.", running.beginCaption());
        assertEquals("Persist off: sprouts despawn 300s after their viewer leaves", running.persistLabel());
        assertEquals("Auto-start on: the listener reconnects when the world loads", running.autoStartLabel());
        assertEquals("Chair: 17,65,13", running.chairLine());
        assertEquals("#streamer", running.detailValues().get(0));
        assertEquals("connected to #streamer", running.detailValues().get(1));
        assertEquals("ValueGood", running.listenerTone());
        assertEquals("ValueWarn", StatusSnapshot.toneFor("connecting"));
        assertEquals("ValueBad", StatusSnapshot.toneFor("reconnecting"));
        assertEquals("ValuePlain", StatusSnapshot.toneFor("stopped"));
        assertEquals("start", StatusSnapshot.runButtonFor(false, "stopped"));
        assertEquals("connecting", StatusSnapshot.runButtonFor(true, "connecting"));
        assertEquals("connecting", StatusSnapshot.runButtonFor(true, "reconnecting"));
        assertEquals("stop", StatusSnapshot.runButtonFor(true, "connected to #streamer"));

        // The loaded-world branch needs a world name, which the fake host never supplies: build the record directly.
        StatusSnapshot loaded = new StatusSnapshot(
            "connected to #streamer", true, true,
            "streamer", 0, 0, "!sprout", false, 0, 3,
            0, 30, 0, 60, 300, 600, true,
            true, false,
            true, 10, 64, 20, 16, 12, "north",
            "Testr World", "11111111-2222-3333-4444-555555555555",
            true, 17, 65, 13,
            "default");
        assertEquals("Pen placed: 16x12 at 10,64,20 in Testr World, camera faces north", loaded.penPlacedLine());
    }
}
