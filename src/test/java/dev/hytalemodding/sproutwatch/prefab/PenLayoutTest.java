package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenLayoutTest {

    private static boolean isChair(String name) {
        return name.contains("Chair");
    }

    @Test void analysesTheBundledPen() throws Exception {
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(PenPrefab.readBundledJson());
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(0, layout.floorY());
        assertEquals(4, layout.clearHeight());
        assertEquals(1, layout.interiorMinX());
        assertEquals(1, layout.interiorMinZ());
        assertEquals(16, layout.sizeX());
        assertEquals(12, layout.sizeZ());
        assertTrue(layout.chairFound());
        assertEquals(8, layout.chairX());
        assertEquals(1, layout.chairY());
        assertEquals(-1, layout.chairZ());
        assertEquals(PenFacing.SOUTH, layout.facing());
    }

    @Test void fallsBackToShrunkBoundingBoxWithoutEmptyBlocks() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 5, 0, "Stone"), new PrefabBlock(9, 5, 0, "Stone"),
            new PrefabBlock(0, 5, 7, "Stone"), new PrefabBlock(9, 5, 7, "Stone"),
            new PrefabBlock(4, 7, 3, "Stone"));
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(5, layout.floorY());
        assertEquals(2, layout.clearHeight());
        assertEquals(1, layout.interiorMinX());
        assertEquals(1, layout.interiorMinZ());
        assertEquals(8, layout.sizeX());
        assertEquals(6, layout.sizeZ());
        assertFalse(layout.chairFound());
        assertEquals(PenFacing.NORTH, layout.facing());
    }

    @Test void facingPointsFromTheChairIntoThePen() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 0, 0, "Soil"),
            new PrefabBlock(1, 1, 1, "Empty"), new PrefabBlock(4, 1, 1, "Empty"),
            new PrefabBlock(1, 1, 4, "Empty"), new PrefabBlock(4, 1, 4, "Empty"),
            new PrefabBlock(6, 1, 2, "Furniture_Village_Chair"));
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(PenFacing.WEST, layout.facing());   // chair east of the pen, camera looks west
    }

    @Test void rejectsEmptyPrefab() {
        assertThrows(IllegalArgumentException.class, () -> PenLayout.analyze(List.of(), PenLayoutTest::isChair));
    }
}
