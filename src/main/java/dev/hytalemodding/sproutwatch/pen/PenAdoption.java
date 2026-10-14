package dev.hytalemodding.sproutwatch.pen;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure: which pen creatures left over from the last session (saved with the world, but unknown to
 * the in-memory registry) are taken back, and which are removed. A creature is kept when its role
 * is one the current creature set spawns and its nameplate is a Twitch login (the spawner shows
 * the login itself for Twitch viewers and test viewers), up to the pen cap, one per viewer.
 * Everything else goes: YouTube nameplates show a display name that cannot be traced back to a
 * viewer key, and other roles belong to a creature set that is no longer selected.
 */
final class PenAdoption {

    /** One NPC found in the pen: its handle, role and nameplate text (null when it has none). */
    record Found<T>(T ref, String role, String nameplate) {}

    /** viewer key to creature for the keepers (in scan order); the rest to remove. */
    record Plan<T>(Map<String, T> adopt, List<T> remove) {}

    private PenAdoption() {}

    static <T> Plan<T> plan(List<Found<T>> found, Set<String> keepRoles, Set<String> alreadyTracked, int maxSprouts) {
        Map<String, T> adopt = new LinkedHashMap<>();
        List<T> remove = new ArrayList<>();
        int room = Math.max(0, maxSprouts - alreadyTracked.size());
        for (Found<T> f : found) {
            String key = viewerKeyOf(f.nameplate());
            boolean keep = key != null && keepRoles.contains(f.role()) && !alreadyTracked.contains(key)
                && !adopt.containsKey(key) && adopt.size() < room;
            if (keep) adopt.put(key, f.ref());
            else remove.add(f.ref());
        }
        return new Plan<>(adopt, remove);
    }

    /** The Twitch login a nameplate shows, or null when it is not one (blank, spaces, a display name). */
    static String viewerKeyOf(String nameplate) {
        if (nameplate == null) return null;
        String trimmed = nameplate.trim();
        String login = SproutwatchConfig.normalizeChannel(trimmed);
        return !login.isEmpty() && login.equalsIgnoreCase(trimmed) ? login : null;
    }
}
