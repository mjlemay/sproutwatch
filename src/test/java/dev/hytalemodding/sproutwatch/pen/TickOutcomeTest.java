package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickOutcomeTest {

    @Test void spawnedTheTestLogin() {
        TickOutcome o = new TickOutcome(Optional.of("alice"), true);
        assertEquals("alice joined the pen.", o.testReply("alice"));
    }

    @Test void noFreeSpotForTheTestLogin() {
        TickOutcome o = new TickOutcome(Optional.of("alice"), false);
        assertEquals("alice could not be placed: no free spot in the pen this tick. It retries on the next tick"
            + " while the listener runs, or run /sproutwatch test alice now again.", o.testReply("alice"));
    }

    @Test void someoneElseWasAheadInLine() {
        assertEquals("bob was ahead of alice in line and joined the pen; alice spawns on a later tick.",
            new TickOutcome(Optional.of("bob"), true).testReply("alice"));
        assertEquals("bob was ahead of alice in line but found no free spot; alice spawns on a later tick.",
            new TickOutcome(Optional.of("bob"), false).testReply("alice"));
    }

    @Test void nothingToSpawn() {
        assertEquals("Nothing spawned for alice: the pen is full of present viewers or alice is not eligible.",
            new TickOutcome(Optional.empty(), false).testReply("alice"));
    }
}
