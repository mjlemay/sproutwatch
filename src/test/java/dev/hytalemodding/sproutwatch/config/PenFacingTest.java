package dev.hytalemodding.sproutwatch.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenFacingTest {

    @Test void parsesCaseInsensitivelyAndDefaultsToNorth() {
        assertEquals(PenFacing.SOUTH, PenFacing.parse("south"));
        assertEquals(PenFacing.EAST, PenFacing.parse(" East "));
        assertEquals(PenFacing.NORTH, PenFacing.parse("sideways"));
        assertEquals(PenFacing.NORTH, PenFacing.parse(null));
    }

    @Test void keyIsLowercaseName() {
        assertEquals("west", PenFacing.WEST.key());
    }

    @Test void towardPicksDominantAxis() {
        assertEquals(PenFacing.SOUTH, PenFacing.toward(0.5, 9.5));
        assertEquals(PenFacing.NORTH, PenFacing.toward(0.5, -9.5));
        assertEquals(PenFacing.EAST, PenFacing.toward(7, 1));
        assertEquals(PenFacing.WEST, PenFacing.toward(-7, 1));
        assertEquals(PenFacing.SOUTH, PenFacing.toward(3, 3)); // tie prefers z
    }

    @Test void deltasMatchDirections() {
        assertEquals(1, PenFacing.SOUTH.dz);
        assertEquals(-1, PenFacing.NORTH.dz);
        assertEquals(1, PenFacing.EAST.dx);
        assertEquals(-1, PenFacing.WEST.dx);
        assertEquals(0, PenFacing.SOUTH.dx);
    }

    @Test void everyFacingRoundTripsThroughDeltasKeyAndToward() {
        for (PenFacing f : PenFacing.values()) {
            assertEquals(1, Math.abs(f.dx) + Math.abs(f.dz));
            assertEquals(f, PenFacing.toward(f.dx, f.dz));
            assertEquals(f, PenFacing.parse(f.key()));
        }
    }

    @Test void seatCameraLooksBackAtTheSeatSoTheSitterIsAtTheTopOfTheScreen() {
        // pen centre (9, 7); seats just outside each side
        assertEquals(PenFacing.NORTH, PenFacing.forSeat(8.5, -0.5, 9, 7));   // north seat: look north at it
        assertEquals(PenFacing.SOUTH, PenFacing.forSeat(8.5, 14.5, 9, 7));   // south seat: look south at it
    }

    @Test void sideSeatsKeepTheLandscapeView() {
        assertEquals(PenFacing.NORTH, PenFacing.forSeat(18.5, 7.5, 9, 7));   // east seat faces north
        assertEquals(PenFacing.SOUTH, PenFacing.forSeat(-0.5, 6.5, 9, 7));   // west seat faces south
    }

    @Test void towardBreaksNegativeTieTowardNorth() {
        assertEquals(PenFacing.NORTH, PenFacing.toward(-3, -3));
    }
}
