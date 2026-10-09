package dev.hytalemodding.sproutwatch.prefab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenPrefabTest {

    @Test void parsesBlocksFromInlineJson() {
        String json = """
            {"version": 8, "blockIdVersion": 11, "anchorX": 0, "anchorY": 0, "anchorZ": 0,
             "blocks": [
               {"x": 1, "y": 2, "z": 3, "name": "Soil_Grass"},
               {"x": -1, "y": 0, "z": 4, "name": "Furniture_Village_Chair", "rotation": 2}
             ],
             "entities": []}
            """;
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        assertEquals(List.of(
            new PrefabBlock(1, 2, 3, "Soil_Grass"),
            new PrefabBlock(-1, 0, 4, "Furniture_Village_Chair")), blocks);
    }

    @Test void bundledPrefabIsOnTheClasspathAndParses() throws Exception {
        String json = PenPrefab.readBundledJson();
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        assertTrue(blocks.size() > 1000, "expected a full pen, got " + blocks.size());
        assertTrue(blocks.stream().anyMatch(b -> b.name().contains("Chair") || b.name().contains("Bench")));
        assertTrue(blocks.stream().anyMatch(b -> b.name().equals("Wood_Hardwood_Fence")));
        assertTrue(blocks.stream().anyMatch(b -> b.name().equals("Empty")));
        assertEquals(4, blocks.stream().filter(b -> b.name().contains("Fence_State_Definitions_Corner")).count(),
            "expected exactly four fence corner blocks");
    }

    @Test void rejectsJsonWithoutBlocks() {
        assertThrows(IllegalArgumentException.class, () -> PenPrefab.parseBlocks("{\"version\": 8}"));
    }
}
