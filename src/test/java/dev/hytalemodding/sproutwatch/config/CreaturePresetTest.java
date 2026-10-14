package dev.hytalemodding.sproutwatch.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreaturePresetTest {

    @Test void presetsInDropdownOrderWithDisplayNames() {
        assertEquals(List.of("kweebecs", "pigs", "chickens", "farm", "goblins"),
            java.util.Arrays.stream(CreaturePreset.values()).map(CreaturePreset::id).toList());
        assertEquals("Farm animals", CreaturePreset.FARM.displayName());
    }

    @Test void pigsAreHalfAdultsHalfBabiesIncludingBoars() {
        assertArrayEquals(new String[]{"Pig|Pig_Wild|Boar", "Pig_Piglet|Pig_Wild_Piglet|Boar_Piglet"},
            CreaturePreset.PIGS.roles(new String[]{"ignored"}));
    }

    @Test void chickensAreHalfAdultsHalfChicks() {
        assertArrayEquals(new String[]{"Chicken|Chicken_Desert", "Chicken_Chick|Chicken_Desert_Chick"},
            CreaturePreset.CHICKENS.roles(new String[]{"ignored"}));
    }

    @Test void farmIsThirdsOfChickensPigsAndCalvesWithoutBoars() {
        String[] farm = CreaturePreset.FARM.roles(new String[]{"ignored"});
        assertArrayEquals(new String[]{"Chicken|Chicken_Desert|Chicken_Chick|Chicken_Desert_Chick",
            "Pig|Pig_Wild|Pig_Piglet|Pig_Wild_Piglet", "Cow_Calf"}, farm);
        assertTrue(java.util.Arrays.stream(farm).noneMatch(e -> e.contains("Boar")));
    }

    @Test void goblinsAreMostlyEverydaySkinsWithRareBurnedAndVoidtaken() {
        String[] pool = CreaturePreset.GOBLINS.roles(new String[]{"ignored"});
        assertEquals(200, pool.length);
        String everyday = "Sprout_Goblin_Moldy|Sprout_Goblin_Mossy|Sprout_Goblin_Muddy|Sprout_Goblin_Rusty|Sprout_Goblin_Stale|Sprout_Goblin_Vapid";
        assertEquals(193, java.util.Arrays.stream(pool).filter(everyday::equals).count());
        assertEquals(5, java.util.Arrays.stream(pool).filter("Sprout_Goblin_Muddy_Burned"::equals).count(), "burned 2.5%");
        assertEquals(2, java.util.Arrays.stream(pool).filter("Sprout_Goblin_Voidtaken"::equals).count(), "voidtaken 1%");
        pool[0] = "changed";
        assertEquals(everyday, CreaturePreset.GOBLINS.roles(new String[]{"ignored"})[0], "roles() hands out a copy");
    }

    @Test void everyGoblinRoleShipsWithItsModelAndJoinsTheDanceGroup() throws Exception {
        String group = read("/Server/NPC/Groups/Sproutwatch/Sprout_Goblin.json");
        for (String role : SproutwatchConfig.splitRoles(CreaturePreset.GOBLINS.roles(new String[0]))) {
            String roleJson = read("/Server/NPC/Roles/Sproutwatch/" + role + ".json");
            assertTrue(roleJson.contains("\"Reference\": \"Template_Goblin_Lab_Scrapper\""), role);
            assertTrue(roleJson.contains("\"Appearance\": \"" + role + "\""), role);
            assertTrue(roleJson.contains("\"WeightWork\": 1"), role + " keeps the lowest work weight the engine accepts (0 fails to spawn)");
            assertTrue(roleJson.contains("\"WeightDance\": 2") && roleJson.contains("\"DanceHearRange\": 4"),
                role + " dances now and then in small groups, not the whole pen at once");
            String model = read("/Server/Models/Sproutwatch/" + role + ".json");
            assertTrue(model.contains("\"MaxScale\": 0.7"), role + " is kid sized");
            assertTrue(group.contains("\"" + role + "\""), role + " in Sprout_Goblin group");
        }
    }

    private static String read(String resource) throws java.io.IOException {
        try (var in = CreaturePresetTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void kweebecsUseTheConfiguredRoles() {
        assertArrayEquals(new String[]{"A", "B|C"}, CreaturePreset.KWEEBECS.roles(new String[]{"A", "B|C"}));
    }

    @Test void parseIsCaseInsensitiveAndFallsBackToKweebecs() {
        assertEquals(CreaturePreset.PIGS, CreaturePreset.parse(" Pigs "));
        assertEquals(CreaturePreset.KWEEBECS, CreaturePreset.parse("dragons"));
        assertEquals(CreaturePreset.KWEEBECS, CreaturePreset.parse(null));
    }

    @Test void theConfigSpawnsThePresetAndSweepsEveryPreset() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals("kweebecs", c.getCreatures());
        assertArrayEquals(c.getRoles(), c.spawnRoles(), "default: the Kweebec roles");
        c.setCreatures("chickens");
        assertArrayEquals(CreaturePreset.CHICKENS.roles(c.getRoles()), c.spawnRoles());
        Set<String> sweep = c.sweepRoles();
        for (String r : List.of("Pig", "Boar_Piglet", "Chicken_Desert_Chick", "Cow_Calf", "Sprout_Sproutling", "Kweebec_Sapling",
            "Sprout_Goblin_Voidtaken")) {
            assertTrue(sweep.contains(r), "sweep must find " + r + " left from any preset: " + sweep);
        }
    }
}
