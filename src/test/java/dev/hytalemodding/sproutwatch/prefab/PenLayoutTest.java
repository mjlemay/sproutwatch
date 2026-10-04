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
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(0, l.floorY());
        assertEquals(4, l.clearHeight());
        assertEquals(1, l.interiorMinX());
        assertEquals(1, l.interiorMinZ());
        assertEquals(16, l.sizeX());
        assertEquals(12, l.sizeZ());
        assertTrue(l.chairFound());
        assertEquals(8, l.chairX());
        assertEquals(1, l.chairY());
        assertEquals(-1, l.chairZ());
        assertEquals(PenFacing.SOUTH, l.facing());
    }

    @Test void fallsBackToShrunkBoundingBoxWithoutEmptyBlocks() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 5, 0, "Stone"), new PrefabBlock(9, 5, 0, "Stone"),
            new PrefabBlock(0, 5, 7, "Stone"), new PrefabBlock(9, 5, 7, "Stone"),
            new PrefabBlock(4, 7, 3, "Stone"));
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(5, l.floorY());
        assertEquals(2, l.clearHeight());
        assertEquals(1, l.interiorMinX());
        assertEquals(1, l.interiorMinZ());
        assertEquals(8, l.sizeX());
        assertEquals(6, l.sizeZ());
        assertFalse(l.chairFound());
        assertEquals(PenFacing.NORTH, l.facing());
    }

    @Test void facingPointsFromTheChairIntoThePen() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 0, 0, "Soil"),
            new PrefabBlock(1, 1, 1, "Empty"), new PrefabBlock(4, 1, 1, "Empty"),
            new PrefabBlock(1, 1, 4, "Empty"), new PrefabBlock(4, 1, 4, "Empty"),
            new PrefabBlock(6, 1, 2, "Furniture_Village_Chair"));
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(PenFacing.WEST, l.facing());   // chair east of the pen, camera looks west
    }

    @Test void rejectsEmptyPrefab() {
        assertThrows(IllegalArgumentException.class, () -> PenLayout.analyze(List.of(), PenLayoutTest::isChair));
    }
}
