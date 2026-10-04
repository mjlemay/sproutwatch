package dev.hytalemodding.sproutwatch.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreaturePresetTest {

    @Test void presetsInDropdownOrderWithDisplayNames() {
        assertEquals(List.of("kweebecs", "pigs", "chickens", "farm"),
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
        for (String r : List.of("Pig", "Boar_Piglet", "Chicken_Desert_Chick", "Cow_Calf", "Sprout_Sproutling", "Kweebec_Sapling")) {
            assertTrue(sweep.contains(r), "sweep must find " + r + " left from any preset: " + sweep);
        }
    }
}
