package dev.hytalemodding.sproutwatch.camera;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SeatedPlayersTest {

    @Test
    void addMakesPlayerSeatedAndRemoveClearsIt() {
        SeatedPlayers seatedPlayers = new SeatedPlayers();
        UUID player = UUID.randomUUID();
        assertFalse(seatedPlayers.isSeated(player));
        assertTrue(seatedPlayers.add(player));
        assertTrue(seatedPlayers.isSeated(player));
        assertTrue(seatedPlayers.remove(player));
        assertFalse(seatedPlayers.isSeated(player));
        assertFalse(seatedPlayers.remove(player), "removing an absent player reports false");
    }

    @Test
    void duplicateAddReturnsFalse() {
        SeatedPlayers seatedPlayers = new SeatedPlayers();
        UUID player = UUID.randomUUID();
        assertTrue(seatedPlayers.add(player));
        assertFalse(seatedPlayers.add(player));
    }

    @Test
    void forgetDropsSeatedPlayerAndToleratesAbsent() {
        SeatedPlayers seatedPlayers = new SeatedPlayers();
        UUID player = UUID.randomUUID();
        seatedPlayers.add(player);
        seatedPlayers.forget(player);
        assertFalse(seatedPlayers.isSeated(player));
        seatedPlayers.forget(player);
    }

    @Test
    void nullPlayerIsNeverSeated() {
        assertFalse(new SeatedPlayers().isSeated(null));
    }

    @Test
    void concurrentAddsAllLand() throws Exception {
        SeatedPlayers seatedPlayers = new SeatedPlayers();
        int threadCount = 8;
        int playersPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        UUID[][] players = new UUID[threadCount][playersPerThread];
        for (int thread = 0; thread < threadCount; thread++) {
            for (int index = 0; index < playersPerThread; index++) players[thread][index] = UUID.randomUUID();
        }
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                UUID[] batch = players[thread];
                executor.submit(() -> {
                    start.await();
                    for (UUID player : batch) assertTrue(seatedPlayers.add(player));
                    return null;
                });
            }
            start.countDown();
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        for (UUID[] batch : players) {
            for (UUID player : batch) assertTrue(seatedPlayers.isSeated(player));
        }
    }
}
