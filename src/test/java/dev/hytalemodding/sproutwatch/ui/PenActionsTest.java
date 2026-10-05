package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.prefab.PenSite;
import dev.hytalemodding.sproutwatch.prefab.PenSnapshot;
import dev.hytalemodding.sproutwatch.prefab.PenTerrain;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pen actions: place, remove, clear, prefab, creatures, max sprouts, tick interval, persist and test viewers. */
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
        assertEquals("Unknown pen prefab 'castle'. Available: default, kweebec_nursery, cobble_pasture.", pen.selectPrefab("castle"));
        assertEquals("Unknown pen prefab ''. Available: default, kweebec_nursery, cobble_pasture.", pen.selectPrefab(null));
        assertEquals(0, host.saveCalls);
        assertEquals("Pen prefab set to Default Lawn. Place the pen to paste it.", pen.selectPrefab(" Default "));
        assertEquals("default", host.config.getPenPrefab());
        assertEquals(1, host.saveCalls);
        assertEquals("Pen prefab set to Kweebec Nursery. Place the pen to paste it.", pen.selectPrefab("kweebec_nursery"));
        assertEquals("kweebec_nursery", host.config.getPenPrefab());
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

    private static FakeHost hostWithPen() {
        FakeHost host = new FakeHost();
        host.config.setPen("world-1", 10, 64, 20, 12, 4, 16);
        host.config.setChair(true, 16, 65, 19);
        host.config.setPenFacing("south");
        return host;
    }

    @Test void removePenWithoutAPenChangesNothing() {
        FakeHost host = new FakeHost();
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        assertEquals("No pen placed; nothing to remove.", new PenActions(host).removePen(replies::add));
        assertEquals(0, host.penTasks.size());
        assertEquals(0, host.stopCalls);
    }

    @Test void removePenWhenThePenWorldIsNotLoadedChangesNothing() {
        FakeHost host = hostWithPen();
        host.running = true;
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        host.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        PenActions pen = new PenActions(host);
        assertEquals("Pen world not loaded; the pen was not removed. Go to the pen's world and try again.", pen.removePen(message -> {}));
        host.penQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Pen world is unloading; try again.", pen.removePen(message -> {}));
        assertTrue(host.config.isPenSet());
        assertTrue(host.running, "the listener keeps running");
        assertEquals(0, host.stopCalls);
        assertEquals(1, host.registry.size());
        assertEquals(0, host.saveCalls);
        assertEquals(0, host.terrain.forgetCalls);
        assertEquals(0, host.changedCalls);
    }

    @Test void removePenStopsTheListenerClearsSproutsRestoresTheGroundAndForgetsThePen() {
        FakeHost host = hostWithPen();
        host.running = true;
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("bob", null, -1, null, 0L, 0L));
        PenSnapshot ground = new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, new BsonDocument());
        host.terrain.saved = ground;
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        PenActions pen = new PenActions(host);

        assertEquals("Removing the pen... (listener stopped)", pen.removePen(replies::add));
        assertEquals(1, host.stopCalls);
        assertFalse(host.running);
        assertTrue(host.config.isPenSet(), "nothing is forgotten until the world thread runs the removal");
        assertEquals(1, host.penTasks.size());

        host.penTasks.get(0).accept(null);
        assertEquals(List.of("Pen removed: cleared 2 sprout(s) and restored the ground."), replies);
        assertEquals(List.of(new PenBounds(10, 64, 20, 12, 16, 4)), host.sweeps);
        assertEquals(0, host.registry.size());
        assertEquals(List.of(new PenSite("world-1", 10, 64, 20, "default")), host.terrain.restoredSites);
        assertEquals(Arrays.asList(ground), host.terrain.restoredGround, "the saved ground goes to restore");
        assertEquals(1, host.terrain.forgetCalls);
        assertNull(host.terrain.saved);
        assertFalse(host.config.isPenSet());
        assertFalse(host.config.isChairSet());
        assertEquals(1, host.saveCalls);
        assertEquals(1, host.changedCalls);
    }

    @Test void removePenWithoutSavedGroundSaysThePenBlocksWereCleared() {
        FakeHost host = hostWithPen();
        host.terrain.outcome = PenTerrain.Restoration.CLEARED;
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        assertEquals("Removing the pen...", new PenActions(host).removePen(replies::add));
        assertEquals(1, host.stopCalls, "stop is asked even when nothing runs");
        host.penTasks.get(0).accept(null);
        assertEquals(List.of("Pen removed: cleared 0 sprout(s) and cleared the pen blocks (no saved ground from before this update)."), replies);
        assertEquals(Arrays.asList((PenSnapshot) null), host.terrain.restoredGround);
        assertFalse(host.config.isPenSet());
    }

    @Test void aFailedRestoreKeepsThePenAndItsSavedGround() {
        FakeHost host = hostWithPen();
        PenSnapshot ground = new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, new BsonDocument());
        host.terrain.saved = ground;
        host.terrain.failure = new IOException("prefab missing");
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        new PenActions(host).removePen(replies::add);
        host.penTasks.get(0).accept(null);
        assertEquals(List.of("Pen removal failed: prefab missing (see server log); the pen is still placed."), replies);
        assertTrue(host.config.isPenSet());
        assertSame(ground, host.terrain.saved);
        assertEquals(0, host.terrain.forgetCalls);
        assertEquals(0, host.saveCalls);
        assertEquals(1, host.changedCalls, "the page is refreshed either way");
    }

    @Test void anUnreadableSavedGroundStopsTheRemovalAndSetsTheFileAside() {
        FakeHost host = hostWithPen();
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        host.terrain.unreadable = new IOException("bad json");
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        PenActions pen = new PenActions(host);
        pen.removePen(replies::add);
        host.penTasks.get(0).accept(null);
        assertEquals(List.of("Pen removal stopped: the saved ground file could not be read (see server log); the pen is still placed."), replies);
        assertEquals(1, host.terrain.setAsideCalls);
        assertTrue(host.config.isPenSet());
        assertEquals(List.of(), host.sweeps, "nothing is cleared");
        assertEquals(1, host.registry.size());
        assertEquals(List.of(), host.terrain.restoredSites);
        assertEquals(0, host.saveCalls);

        // The next attempt finds no file and uses the air fallback on purpose.
        host.terrain.outcome = PenTerrain.Restoration.CLEARED;
        replies.clear();
        pen.removePen(replies::add);
        host.penTasks.get(1).accept(null);
        assertEquals(List.of("Pen removed: cleared 1 sprout(s) and cleared the pen blocks (no saved ground from before this update)."), replies);
        assertFalse(host.config.isPenSet());
    }

    @Test void aRemovalQueuedForAnotherPenDoesNothing() {
        FakeHost host = hostWithPen();
        host.penQueue = ActionsHost.WorldQueue.QUEUED;
        List<String> replies = new ArrayList<>();
        new PenActions(host).removePen(replies::add);
        host.config.setPen("world-1", 50, 64, 60, 12, 4, 16);   // moved before the world thread got to it
        host.penTasks.get(0).accept(null);
        assertEquals(List.of("The pen changed before it could be removed; nothing was removed."), replies);
        assertTrue(host.config.isPenSet());
        assertEquals(50, host.config.getPenX());
        assertEquals(List.of(), host.sweeps);
        assertEquals(List.of(), host.terrain.restoredSites);
        assertEquals(0, host.terrain.forgetCalls);
        assertEquals(0, host.saveCalls);
    }

    // ---- moving the pen: FakeHost.penWorld() is null and the tests pass a null world, so the old
    // pen counts as being in the same world as the new one.

    @Test void movingThePenInTheSameWorldRestoresTheOldGroundThenPlacesTheNewPen() throws IOException {
        FakeHost host = hostWithPen();
        host.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        PenSnapshot oldGround = new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, new BsonDocument());
        host.terrain.saved = oldGround;
        String reply = new PenActions(host).placeAt(null, new Vector3i(30, 70, 40));
        assertEquals("Pen placed. Cleared 1 sprout(s) from the old pen and restored the ground.", reply);
        assertEquals(List.of("restore", "forget", "place", "remember"), host.terrain.calls);
        assertEquals(List.of(new PenSite("world-1", 10, 64, 20, "default")), host.terrain.restoredSites);
        assertEquals(Arrays.asList(oldGround), host.terrain.restoredGround);
        assertEquals(List.of(new PenBounds(10, 64, 20, 12, 16, 4)), host.sweeps);
        assertEquals("world-2", host.config.getPenWorld(), "the new pen is recorded");
        assertEquals(24, host.config.getPenX());
        assertNotNull(host.terrain.saved, "the new pen's ground is saved");
        assertEquals("world-2", host.terrain.saved.worldUuid());
        assertEquals(2, host.saveCalls, "saved once when the old pen is forgotten and once after the paste");
    }

    @Test void aFailedOldRestoreStopsTheMoveAndKeepsTheOldPenAndItsGround() throws IOException {
        FakeHost host = hostWithPen();
        PenSnapshot oldGround = new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, new BsonDocument());
        host.terrain.saved = oldGround;
        host.terrain.failure = new IOException("chunk trouble");
        String reply = new PenActions(host).placeAt(null, new Vector3i(30, 70, 40));
        assertEquals("Pen move stopped: restoring the old pen's ground failed (see server log); "
            + "the old pen is still placed and its saved ground is kept.", reply);
        assertEquals(List.of("restore"), host.terrain.calls, "no forget, no paste");
        assertTrue(host.config.isPenSet());
        assertEquals("world-1", host.config.getPenWorld());
        assertEquals(10, host.config.getPenX());
        assertSame(oldGround, host.terrain.saved);
        assertEquals(0, host.saveCalls);
    }

    @Test void aPasteThatFailsAfterTheOldPenIsGoneLeavesNoPenInConfig() {
        FakeHost host = hostWithPen();
        host.terrain.saved = new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, new BsonDocument());
        host.terrain.placeFailure = new IOException("prefab missing");
        PenActions pen = new PenActions(host);
        assertThrows(IOException.class, () -> pen.placeAt(null, new Vector3i(30, 70, 40)));
        assertEquals(List.of("restore", "forget", "place"), host.terrain.calls);
        assertFalse(host.config.isPenSet(), "the config never points at the restored old site");
        assertNull(host.terrain.saved);
        assertEquals(1, host.saveCalls, "the forgotten pen is saved before the paste");
    }

    @Test void anUnreadableOldGroundStopsTheMoveAndSetsTheFileAside() throws IOException {
        FakeHost host = hostWithPen();
        host.terrain.unreadable = new IOException("bad json");
        String reply = new PenActions(host).placeAt(null, new Vector3i(30, 70, 40));
        assertEquals("Pen move stopped: the old pen's saved ground file could not be read (see server log); "
            + "the old pen is still placed. Place again to clear its blocks to air instead.", reply);
        assertEquals(List.of("setAside"), host.terrain.calls);
        assertTrue(host.config.isPenSet());
        assertEquals(List.of(), host.sweeps);
    }

    @Test void placingTheFirstPenSkipsTheTakeAway() throws IOException {
        FakeHost host = new FakeHost();
        assertEquals("Pen placed.", new PenActions(host).placeAt(null, new Vector3i(30, 70, 40)));
        assertEquals(List.of("place", "remember"), host.terrain.calls);
        assertEquals(1, host.saveCalls);
    }
}
