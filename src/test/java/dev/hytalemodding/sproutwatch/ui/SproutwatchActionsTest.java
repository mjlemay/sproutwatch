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
        final SproutwatchConfig config = new SproutwatchConfigAccess().fresh();
        final ChatRoster roster = new ChatRoster(config::ignoredViewers, config::getQueueCommand, new SproutQueue());
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

        @Override public SproutwatchConfig config() { return config; }
        @Override public void saveConfig() { saveCalls++; }
        @Override public Logger logger() { return logger; }
        @Override public ChatRoster roster() { return roster; }
        @Override public PenRegistry registry() { return registry; }
        @Override public Set<String> roleSet() { return config.sweepRoles(); }
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
            boolean twitchReady = config.twitchReady();
            boolean youTubeReady = config.youTubeConfigured();
            if (!running) return StatusSnapshot.sourceStates(null, twitchReady, null, youTubeReady);
            return StatusSnapshot.sourceStates(
                twitchReady ? "connected to #" + config.getTwitchChannel() : null, twitchReady,
                youTubeReady && !youTubeFails ? "connected to YouTube (" + YouTubeStatus.target(config) + ")" : null, youTubeReady);
        }
        @Override public YouTubeStatus youTubeStatus() { return YouTubeStatus.of(config, null, clock); }
        @Override public String listenerState() { return StatusSnapshot.joinStates(sourceStates()); }
        @Override public boolean listenerRunning() { return running; }
        @Override public boolean feedAcknowledged() { return acked; }
        /** Contract: a start always stops the current run first, refused or not. */
        @Override public String startListener() {
            startCalls++;
            running = false;
            String error = startError != null ? startError : refuseLikePlugin ? config.nothingToStartReason() : null;
            if (error != null) return error;
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
        @Override public java.util.concurrent.CompletableFuture<String> lookUpViewerChannelId(String viewerHandle) {
            lookups.add(viewerHandle);
            java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
            lookupFutures.add(f);
            return f;
        }
    }

    @Test void setChannelWhenStoppedSavesAndTellsHowToStart() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Channel set to #streamer. Run /sproutwatch start to begin.", a.setChannel("#Streamer!"));
        assertEquals("streamer", host.config.getTwitchChannel());
        assertEquals(1, host.saveCalls);
        assertEquals(0, host.startCalls);
    }

    @Test void setChannelWhileRunningRestartsListener() {
        FakeHost host = new FakeHost();
        host.running = true;
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Channel set to #streamer; listener restarted.", a.setChannel("streamer"));
        assertEquals(1, host.startCalls);
        host.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(host.startError, a.setChannel("other"));
        assertEquals("other", host.config.getTwitchChannel(), "the channel is saved even when the restart is refused");
        assertEquals(2, host.saveCalls);
    }

    @Test void setChannelRejectsInvalidInput() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Invalid channel name.", a.setChannel("!!!"));
        assertEquals("Invalid channel name.", a.setChannel(null));
        assertEquals(0, host.saveCalls);
    }

    @Test void startListenerPassesRefusalThrough() {
        FakeHost host = new FakeHost();
        host.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals(host.startError, new SproutwatchActions(host).startListener());
    }

    @Test void startAndStopWording() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", a.startListener());
        assertTrue(host.running);
        assertEquals("Sproutwatch stopped.", a.stopListener());
        assertEquals("Sproutwatch was not running.", a.stopListener());
        assertEquals(2, host.stopCalls);
    }

    @Test void allowAddRemoveNormalizesAndSaves() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Added alice to the allow list.", a.addAllow("@Alice"));
        assertEquals("alice is already on the allow list.", a.addAllow("alice"));
        assertEquals(Set.of("alice"), host.config.allowedViewers());
        assertEquals("Removed alice from the allow list.", a.removeAllow("ALICE"));
        assertEquals("alice is not on the allow list.", a.removeAllow("alice"));
        assertEquals(2, host.saveCalls, "saved once per successful change");
    }

    @Test void ignoreAddPartsTheViewerAndRemoveRestores() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.roster.apply(new RosterEvent.Chat("spammer", "!sprout"), 1L);
        assertEquals(1, host.roster.size());
        assertEquals(1, host.roster.queue().size());
        assertEquals("Added spammer to the ignore list.", a.addIgnore("Spammer"));
        assertEquals(0, host.roster.size(), "an ignored viewer leaves the roster at once");
        assertEquals(0, host.roster.queue().size());
        assertTrue(host.config.ignoredViewers().contains("spammer"));
        assertEquals("spammer is already on the ignore list.", a.addIgnore("spammer"));
        assertEquals("Removed spammer from the ignore list.", a.removeIgnore("spammer"));
        assertEquals("spammer is not on the ignore list.", a.removeIgnore("spammer"));
        assertEquals(2, host.saveCalls);
    }

    @Test void invalidLoginsAreRejectedEverywhere() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        for (String bad : new String[]{null, "", "   ", "!!!"}) {
            assertEquals("Invalid user name.", a.addAllow(bad));
            assertEquals("Invalid user name.", a.removeAllow(bad));
            assertEquals("Invalid user name.", a.addIgnore(bad));
            assertEquals("Invalid user name.", a.removeIgnore(bad));
        }
        assertEquals(0, host.saveCalls);
    }

    @Test void persistSetSavesAndExplains() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Persist is now off: sprouts despawn 300s after their viewer leaves (any already past that window go on the next tick).", a.setPersist(false));
        assertFalse(host.config.isPersistSprouts());
        assertEquals("Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced.", a.setPersist(true));
        assertTrue(host.config.isPersistSprouts());
        assertEquals(2, host.saveCalls);
    }

    @Test void autoStartTogglesConfigAndSaves() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Auto-start on boot is now on: the listener reconnects when the world loads.", a.setAutoStart(true));
        assertTrue(host.config.isAutoStartOnBoot());
        assertEquals("Auto-start on boot is now off: press Start listener after each launch.", a.setAutoStart(false));
        assertFalse(host.config.isAutoStartOnBoot());
        assertEquals(2, host.saveCalls);
    }

    @Test void maxSproutsClampsToAtLeastOneAndSaves() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Max sprouts is now 12.", a.setMaxSprouts(12));
        assertEquals(12, host.config.getMaxSprouts());
        assertEquals("Max sprouts is now 1.", a.setMaxSprouts(0));
        assertEquals(1, host.config.getMaxSprouts());
        assertEquals(2, host.saveCalls);
    }

    @Test void removeGuestDropsTheRosterEntryAndDespawnsOnThePenWorld() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("alice is not a test viewer (/sproutwatch test list).", a.removeGuest("Alice"));
        host.roster.addGuest("alice", 0L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("alice removed from the test viewers; despawning their sprout...", a.removeGuest("alice"));
        assertEquals(Set.of(), host.roster.guests());
        assertEquals(1, host.penTasks.size());
        host.roster.addGuest("bob", 0L);
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("bob removed from the test viewers (pen world not loaded).", a.removeGuest("bob"));
    }

    @Test void setFilterSwitchesModeAndSaves() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (the list is empty, so nobody until you add someone).",
            a.setFilter(true));
        assertTrue(host.config.isAllowMode());
        a.addAllow("alice");
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (1 listed).", a.setFilter(true));
        assertEquals("Filter is now the ignore list: everyone in chat except 3 ignored.", a.setFilter(false));
        assertFalse(host.config.isAllowMode());
        assertEquals(4, host.saveCalls, "three filter changes and one addAllow");
        a.setFilter(true);
        assertEquals("Filter: allow list (1 allowed)", a.snapshot().filterLine());
    }

    @Test void switchingCreaturesSavesAndClearsThePenButKeepsGuests() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.roster.addGuest("alice", 0L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Creatures: Pigs. Clearing the pen; it refills one per tick.", a.setCreatures("pigs"));
        assertEquals("pigs", host.config.getCreatures());
        assertEquals(1, host.saveCalls);
        assertEquals(1, host.penTasks.size(), "the clear is queued on the pen world");
        assertEquals(Set.of("alice"), host.roster.guests(), "test viewers keep their place");
        assertEquals("Creatures are already Pigs.", a.setCreatures("Pigs"));
        assertEquals(1, host.penTasks.size(), "no clear when nothing changed");
    }

    @Test void beginStartsTheListenerOrReportsWhyNot() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(Optional.of(host.startError), a.begin());
        host.startError = null;
        assertEquals(Optional.empty(), a.begin());
        assertEquals(2, host.startCalls, "the failed attempt and the successful one");
        assertTrue(host.running);
        assertEquals(Optional.empty(), a.begin(), "already running: nothing to start, just close");
        assertEquals(2, host.startCalls);
    }

    @Test void clearForgetsGuestsSoTheyDoNotRespawn() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.roster.addGuest("alice", 0L);
        host.roster.apply(new RosterEvent.Join("bob"), 5L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", a.clear());
        assertEquals(Set.of(), host.roster.guests());
        assertEquals(Map.of("bob", 5L), host.roster.snapshot());
    }

    @Test void loweringMaxBelowThePenCountTrimsTheSurplusOnThePenWorld() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.registry.put(new PenRegistry.Entry("a", null, -1, null, 0L, 10L));
        host.registry.put(new PenRegistry.Entry("b", null, -1, null, 0L, 20L));
        host.registry.put(new PenRegistry.Entry("c", null, -1, null, 0L, 30L));
        host.roster.apply(new RosterEvent.Chat("c", ""), 5L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Max sprouts is now 1; removing 2 extra sprout(s)...", a.setMaxSprouts(1));
        assertEquals(1, host.penTasks.size(), "the despawn is queued on the pen world thread");
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        host.config.setMaxSprouts(3);
        assertEquals("Max sprouts is now 2; pen world not loaded, forgot 1 tracked sprout(s).", a.setMaxSprouts(2));
        assertEquals(2, host.registry.size());
    }

    @Test void tickSecondsClampsAndRestartsRunningTicker() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Tick interval is now 5s.", a.setTickSeconds(1));
        assertEquals(0, host.restartCalls, "a stopped ticker is not re-armed");
        host.tickerRunning = true;
        assertEquals("Tick interval is now 30s.", a.setTickSeconds(30));
        assertEquals(1, host.restartCalls);
        assertEquals(2, host.saveCalls);
    }

    @Test void selectPrefabRejectsUnknownAndSavesKnown() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals("Unknown pen prefab 'castle'. Available: default.", a.selectPrefab("castle"));
        assertEquals("Unknown pen prefab ''. Available: default.", a.selectPrefab(null));
        assertEquals(0, host.saveCalls);
        assertEquals("Pen prefab set to default. Place the pen to paste it.", a.selectPrefab(" Default "));
        assertEquals("default", host.config.getPenPrefab());
        assertEquals(1, host.saveCalls);
    }

    @Test void placeReportsQueueOutcome() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.playerQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Placing the pen...", a.place(null));
        assertEquals(1, host.playerTasks.size(), "the paste runs on the player's world thread");
        host.playerQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Your world is not loaded.", a.place(null));
        host.playerQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Your world is unloading; try again.", a.place(null));
        assertEquals(1, host.playerTasks.size());
    }

    @Test void clearReportsQueueOutcomeAndForgetsWhenUnloaded() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Pen world not loaded; forgot 1 tracked sprout(s).", a.clear());
        assertEquals(0, host.registry.size());
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", a.clear());
        assertEquals(1, host.penTasks.size());
        host.penQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Pen world is unloading; try again.", a.clear());
        assertEquals(1, host.penTasks.size());
    }

    @Test void snapshotReflectsHostAndConfig() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.config.setChair(true, 17, 65, 13);
        host.config.setPenFacing("south");
        host.config.addAllow("alice");
        host.config.addAllow("dave");
        host.config.addAllow("erin");
        host.roster.apply(new RosterEvent.Chat("bob", "!sprout"), 1L);
        host.roster.apply(new RosterEvent.Chat("frank", "!sprout"), 2L);
        host.roster.apply(new RosterEvent.Chat("grace", "hello"), 3L);
        host.roster.apply(new RosterEvent.Chat("heidi", "hello"), 4L);
        host.roster.apply(new RosterEvent.Chat("ivan", "hello"), 5L);
        host.registry.put(new PenRegistry.Entry("bob", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("frank", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("grace", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("heidi", null, -1, null, 0L, 0L));
        host.registry.retire("carol");
        host.running = true;
        host.acked = true;
        host.tickerRunning = true;
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("connected to #streamer", s.listenerState());
        assertTrue(s.listenerRunning());
        assertTrue(s.feedAcknowledged());
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
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
        assertEquals(String.join("\n",
            "Listener: stopped",
            "Channel: (unset)",
            "Seen in chat: 0 (chatters since Start)",
            "Queue: 0 waiting (typed !sprout in chat)",
            "Filter: ignore list (everyone in chat except 3 ignored)",
            "Pen: 0/30 sprouts, tick 60s, grace 300s, ticker stopped",
            "Persist: on (longest-gone replaced at the cap)",
            "Pen: not placed (run /sproutwatch place)"), a.statusReport());

        host.config.setTwitchChannel("streamer");
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.running = true;
        host.registry.retire("carol");
        String running = a.statusReport();
        assertTrue(running.contains("Listener: connected to #streamer\n"), running);
        assertTrue(running.contains("Channel: #streamer\n"), running);
        assertTrue(running.contains("Twitch JOIN/PART feed: OFF (Twitch did not grant membership; only viewers who chat will appear)\n"), running);
        assertTrue(running.contains("Retired (killed by a player): 1\n"), running);
        assertTrue(running.contains("Pen placed: 16x12 at 10,64,20 in an unloaded world (11111111-2222-3333-4444-555555555555), camera faces north\n"), running);
        assertTrue(running.endsWith("Chair: (none; use /sproutwatch camera)"), running);
    }

    @Test void snapshotLabelsForThePage() {
        FakeHost host = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(host);
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

        host.config.setTwitchChannel("streamer");
        host.config.setPersistSprouts(false);
        host.config.setAutoStartOnBoot(true);
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.config.setChair(true, 17, 65, 13);
        host.running = true;
        host.acked = true;
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
