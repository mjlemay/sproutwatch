package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SproutSpawnerTest {

    @Test void plainEntriesArePickedAsIs() {
        assertEquals("Kweebec_Seedling", SproutSpawner.pickRole(new String[] {"Kweebec_Seedling"}, new Random(1)));
    }

    @Test void aGroupIsOneEntryThatPicksOneOfItsRoles() {
        // Entries are equally likely; inside a "|" group each role is equally likely: Saplings stay a third
        // of the pen while their colour varies.
        String[] pool = {"Seed", "Sprout", "Red | Blue|Green"};
        Map<String, Integer> n = new HashMap<>();
        Random r = new Random(42);
        for (int i = 0; i < 9000; i++) n.merge(SproutSpawner.pickRole(pool, r), 1, Integer::sum);
        assertEquals(java.util.Set.of("Seed", "Sprout", "Red", "Blue", "Green"), n.keySet());
        assertTrue(Math.abs(n.get("Seed") - 3000) < 250, n.toString());
        assertTrue(Math.abs(n.get("Red") - 1000) < 200, n.toString());
    }
}
