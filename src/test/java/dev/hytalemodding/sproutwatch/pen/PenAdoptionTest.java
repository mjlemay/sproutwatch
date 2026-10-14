package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PenAdoptionTest {

    private static final Set<String> GOBLINS = Set.of("Sprout_Goblin_Moldy", "Sprout_Goblin_Rusty");

    private static PenAdoption.Found<String> npc(String id, String role, String nameplate) {
        return new PenAdoption.Found<>(id, role, nameplate);
    }

    @Test void creaturesFromLastSessionAreTakenBackByTheirNameplate() {
        var plan = PenAdoption.plan(List.of(
            npc("a", "Sprout_Goblin_Moldy", "filbro"),
            npc("b", "Sprout_Goblin_Rusty", "gob1")), GOBLINS, Set.of(), 30);
        assertEquals(Map.of("filbro", "a", "gob1", "b"), plan.adopt());
        assertTrue(plan.remove().isEmpty());
    }

    @Test void creaturesOfAnotherCreatureSetAreRemoved() {
        var plan = PenAdoption.plan(List.of(npc("k", "Kweebec_Seedling", "filbro")), GOBLINS, Set.of(), 30);
        assertTrue(plan.adopt().isEmpty());
        assertEquals(List.of("k"), plan.remove());
    }

    @Test void nameplatesThatAreNotATwitchLoginAreRemoved() {
        var plan = PenAdoption.plan(List.of(
            npc("yt", "Sprout_Goblin_Moldy", "Some YouTube Name"),
            npc("none", "Sprout_Goblin_Moldy", null),
            npc("blank", "Sprout_Goblin_Moldy", "  ")), GOBLINS, Set.of(), 30);
        assertTrue(plan.adopt().isEmpty());
        assertEquals(List.of("yt", "none", "blank"), plan.remove());
    }

    @Test void aMixedCaseLoginIsKeptLowercased() {
        var plan = PenAdoption.plan(List.of(npc("a", "Sprout_Goblin_Moldy", "FilBro")), GOBLINS, Set.of(), 30);
        assertEquals(Map.of("filbro", "a"), plan.adopt());
    }

    @Test void oneCreaturePerViewerAndNoneForViewersAlreadyTracked() {
        var plan = PenAdoption.plan(List.of(
            npc("a", "Sprout_Goblin_Moldy", "filbro"),
            npc("b", "Sprout_Goblin_Rusty", "filbro"),
            npc("c", "Sprout_Goblin_Rusty", "gob1")), GOBLINS, Set.of("gob1"), 30);
        assertEquals(Map.of("filbro", "a"), plan.adopt());
        assertEquals(List.of("b", "c"), plan.remove());
    }

    @Test void neverKeepsMoreThanTheCap() {
        var plan = PenAdoption.plan(List.of(
            npc("a", "Sprout_Goblin_Moldy", "one"),
            npc("b", "Sprout_Goblin_Moldy", "two"),
            npc("c", "Sprout_Goblin_Moldy", "three")), GOBLINS, Set.of("tracked"), 3);
        assertEquals(2, plan.adopt().size());
        assertEquals(List.of("c"), plan.remove());
    }
}
