package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pen actions: place, clear, prefab, creatures, max sprouts, tick interval, persist and test viewers. */
class PenActionsTest {

    @Test void persistSetSavesAndExplains() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        assertEquals("Persist is now off: sprouts despawn 300s after their viewer leaves (any already past that window go on the next tick).", pen.setPersist(false));
        assertFalse(host.config.isPersistSprouts());
        assertEquals("Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced.", pen.setPersist(true));
        assertTrue(host.config.isPersistSprouts());
        assertEquals(2, host.saveCalls);
    }

    @Test void maxSproutsClampsToAtLeastOneAndSaves() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        assertEquals("Max sprouts is now 12.", pen.setMaxSprouts(12));
        assertEquals(12, host.config.getMaxSprouts());
        assertEquals("Max sprouts is now 1.", pen.setMaxSprouts(0));
        assertEquals(1, host.config.getMaxSprouts());
        assertEquals(2, host.saveCalls);
    }

    @Test void removeGuestDropsTheRosterEntryAndDespawnsOnThePenWorld() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        assertEquals("alice is not a test viewer (/sproutwatch test list).", pen.removeGuest("Alice"));
        host.roster.addGuest("alice", 0L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("alice removed from the test viewers; despawning their sprout...", pen.removeGuest("alice"));
        assertEquals(Set.of(), host.roster.guests());
        assertEquals(1, host.penTasks.size());
        host.roster.addGuest("bob", 0L);
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("bob removed from the test viewers (pen world not loaded).", pen.removeGuest("bob"));
    }

    @Test void switchingCreaturesSavesAndClearsThePenButKeepsGuests() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        host.roster.addGuest("alice", 0L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Creatures: Pigs. Clearing the pen; it refills one per tick.", pen.setCreatures("pigs"));
        assertEquals("pigs", host.config.getCreatures());
        assertEquals(1, host.saveCalls);
        assertEquals(1, host.penTasks.size(), "the clear is queued on the pen world");
        assertEquals(Set.of("alice"), host.roster.guests(), "test viewers keep their place");
        assertEquals("Creatures are already Pigs.", pen.setCreatures("Pigs"));
        assertEquals(1, host.penTasks.size(), "no clear when nothing changed");
    }

    @Test void clearForgetsGuestsSoTheyDoNotRespawn() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        host.roster.addGuest("alice", 0L);
        host.roster.apply(new RosterEvent.Join("bob"), 5L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", pen.clear());
        assertEquals(Set.of(), host.roster.guests());
        assertEquals(Map.of("bob", 5L), host.roster.snapshot());
    }

    @Test void loweringMaxBelowThePenCountTrimsTheSurplusOnThePenWorld() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        host.registry.put(new PenRegistry.Entry("a", null, -1, null, 0L, 10L));
        host.registry.put(new PenRegistry.Entry("b", null, -1, null, 0L, 20L));
        host.registry.put(new PenRegistry.Entry("c", null, -1, null, 0L, 30L));
        host.roster.apply(new RosterEvent.Chat("c", ""), 5L);
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Max sprouts is now 1; removing 2 extra sprout(s)...", pen.setMaxSprouts(1));
        assertEquals(1, host.penTasks.size(), "the despawn is queued on the pen world thread");
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        host.config.setMaxSprouts(3);
        assertEquals("Max sprouts is now 2; pen world not loaded, forgot 1 tracked sprout(s).", pen.setMaxSprouts(2));
        assertEquals(2, host.registry.size());
    }

    @Test void tickSecondsClampsAndRestartsRunningTicker() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        assertEquals("Tick interval is now 5s.", pen.setTickSeconds(1));
        assertEquals(0, host.restartCalls, "a stopped ticker is not re-armed");
        host.tickerRunning = true;
        assertEquals("Tick interval is now 30s.", pen.setTickSeconds(30));
        assertEquals(1, host.restartCalls);
        assertEquals(2, host.saveCalls);
    }

    @Test void selectPrefabRejectsUnknownAndSavesKnown() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        assertEquals("Unknown pen prefab 'castle'. Available: default.", pen.selectPrefab("castle"));
        assertEquals("Unknown pen prefab ''. Available: default.", pen.selectPrefab(null));
        assertEquals(0, host.saveCalls);
        assertEquals("Pen prefab set to default. Place the pen to paste it.", pen.selectPrefab(" Default "));
        assertEquals("default", host.config.getPenPrefab());
        assertEquals(1, host.saveCalls);
    }

    @Test void placeReportsQueueOutcome() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        host.playerQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Placing the pen...", pen.place(null));
        assertEquals(1, host.playerTasks.size(), "the paste runs on the player's world thread");
        host.playerQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Your world is not loaded.", pen.place(null));
        host.playerQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Your world is unloading; try again.", pen.place(null));
        assertEquals(1, host.playerTasks.size());
    }

    @Test void clearReportsQueueOutcomeAndForgetsWhenUnloaded() {
        FakeHost host = new FakeHost();
        PenActions pen = new PenActions(host);
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Pen world not loaded; forgot 1 tracked sprout(s).", pen.clear());
        assertEquals(0, host.registry.size());
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", pen.clear());
        assertEquals(1, host.penTasks.size());
        host.penQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Pen world is unloading; try again.", pen.clear());
        assertEquals(1, host.penTasks.size());
    }
}
