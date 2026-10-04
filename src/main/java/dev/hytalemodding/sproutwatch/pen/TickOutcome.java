package dev.hytalemodding.sproutwatch.pen;

import java.util.Optional;

/**
 * What one tick did about spawning: the login the reconciler chose (if any) and whether the
 * spawner actually placed it. The spawner fails quietly (FINE log) when the pen has no validated
 * free spot, so {@code /sproutwatch test <login> now} uses this to tell the sender the truth.
 */
public record TickOutcome(Optional<String> candidate, boolean spawned) {

    /** The chat line for the sender of {@code /sproutwatch test <login> now}, after the forced tick. */
    public String testReply(String login) {
        if (candidate.isEmpty()) {
            return "Nothing spawned for " + login + ": the pen is full of present viewers or " + login + " is not eligible.";
        }
        String chosen = candidate.get();
        if (chosen.equals(login)) {
            return spawned
                ? login + " joined the pen."
                : login + " could not be placed: no free spot in the pen this tick. It retries on the next tick"
                    + " while the listener runs, or run /sproutwatch test " + login + " now again.";
        }
        return chosen + " was ahead of " + login + " in line "
            + (spawned ? "and joined the pen; " : "but found no free spot; ")
            + login + " spawns on a later tick.";
    }
}
