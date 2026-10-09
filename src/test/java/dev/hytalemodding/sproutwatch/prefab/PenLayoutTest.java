package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenLayoutTest {

    private static boolean isChair(String name) {
        return name.contains("Chair") || name.contains("Bench");
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
        assertEquals(PenFacing.NORTH, layout.facing());   // camera on the south side looks back at the chair
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

    @Test void facingFollowsTheSeatSide() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 0, 0, "Soil"),
            new PrefabBlock(1, 1, 1, "Empty"), new PrefabBlock(4, 1, 1, "Empty"),
            new PrefabBlock(1, 1, 4, "Empty"), new PrefabBlock(4, 1, 4, "Empty"),
            new PrefabBlock(6, 1, 2, "Furniture_Village_Chair"));
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(PenFacing.NORTH, layout.facing());   // chair east of the pen: side seats face north
    }

    @Test void prefersTheNorthSeatNearestTheCentreWhenThereAreSeveral() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 0, 0, "Soil"),
            new PrefabBlock(1, 1, 1, "Empty"), new PrefabBlock(6, 1, 1, "Empty"),
            new PrefabBlock(1, 1, 4, "Empty"), new PrefabBlock(6, 1, 4, "Empty"),
            new PrefabBlock(0, 1, 2, "Furniture_Tavern_Bench"),    // west side, first in file
            new PrefabBlock(1, 1, 0, "Furniture_Tavern_Bench"),    // north side, off centre
            new PrefabBlock(4, 1, 0, "Furniture_Tavern_Bench"),    // north side, centre is x 4.0: tie with x 3
            new PrefabBlock(3, 1, 0, "Furniture_Tavern_Bench"),
            new PrefabBlock(3, 1, 5, "Furniture_Tavern_Bench"));   // south side
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertTrue(layout.chairFound());
        assertEquals(3, layout.chairX());
        assertEquals(0, layout.chairZ());
        assertEquals(PenFacing.NORTH, layout.facing());
    }

    @Test void ignoresEmptyBlocksOutsideTheFenceRing() {
        assertRingKeepsInterior("Wood_Hardwood_Fence");
    }

    @Test void aWallRingCountsAsAFence() {
        assertRingKeepsInterior("Rock_Basalt_Cobble_Wall");
    }

    private static void assertRingKeepsInterior(String ring) {
        List<PrefabBlock> blocks = new java.util.ArrayList<>();
        blocks.add(new PrefabBlock(0, 0, 0, "Soil"));
        for (int i = 0; i <= 5; i++) {
            blocks.add(new PrefabBlock(i, 1, 0, ring));
            blocks.add(new PrefabBlock(i, 1, 5, ring));
            blocks.add(new PrefabBlock(0, 1, i, ring));
            blocks.add(new PrefabBlock(5, 1, i, ring));
        }
        blocks.add(new PrefabBlock(1, 1, 1, "Empty"));
        blocks.add(new PrefabBlock(4, 1, 4, "Empty"));
        blocks.add(new PrefabBlock(-1, 1, 3, "Empty"));                   // beside a bench, outside the fence
        blocks.add(new PrefabBlock(2, 1, 7, "Empty"));
        blocks.add(new PrefabBlock(-1, 1, 2, "Furniture_Tavern_Bench"));
        PenLayout layout = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(1, layout.interiorMinX());
        assertEquals(1, layout.interiorMinZ());
        assertEquals(4, layout.sizeX());
        assertEquals(4, layout.sizeZ());
    }

    @Test void rejectsEmptyPrefab() {
        assertThrows(IllegalArgumentException.class, () -> PenLayout.analyze(List.of(), PenLayoutTest::isChair));
    }
}
