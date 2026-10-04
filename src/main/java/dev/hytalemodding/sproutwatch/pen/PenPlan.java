package dev.hytalemodding.sproutwatch.pen;

import java.util.List;
import java.util.Optional;

/** What one tick should do: at most one spawn, any number of despawns (sorted by viewer key). */
public record PenPlan(Optional<String> spawn, List<String> despawn) {}
