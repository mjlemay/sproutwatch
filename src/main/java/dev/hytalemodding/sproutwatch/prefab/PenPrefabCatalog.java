package dev.hytalemodding.sproutwatch.prefab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the bundled pen prefabs (config key PenPrefab, settings page dropdown). Only "default"
 * exists today; add a resource under Server/Prefabs/Sproutwatch/ and one RESOURCES entry to ship
 * another. Unknown names fall back to the default so a hand-edited config can never break placement.
 */
public final class PenPrefabCatalog {

    public static final String DEFAULT = "default";

    private static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    static {
        RESOURCES.put(DEFAULT, PenPrefab.RESOURCE);
    }

    private PenPrefabCatalog() {}

    /** Immutable, default first, in declaration order. */
    public static List<String> names() {
        return List.copyOf(RESOURCES.keySet());
    }

    /** Trimmed, lowercase; "" for null or blank. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean contains(String name) {
        return RESOURCES.containsKey(normalize(name));
    }

    /** Classpath resource for the named prefab, or the default's when the name is unknown. */
    public static String resourceFor(String name) {
        String res = RESOURCES.get(normalize(name));
        return res != null ? res : RESOURCES.get(DEFAULT);
    }
}
