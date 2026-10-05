package dev.hytalemodding.sproutwatch.prefab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the bundled pen prefabs (config key PenPrefab, settings page dropdown). Each entry has an
 * id (saved in the config), a label (shown in the dropdown and replies) and a resource under
 * Server/Prefabs/Sproutwatch/. To ship another, add the file and one add(...) line. Default Lawn
 * keeps the id "default" so configs and pen snapshots saved before the catalog grew still match.
 * Unknown names fall back to the default so a hand-edited config can never break placement.
 */
public final class PenPrefabCatalog {

    public static final String DEFAULT = "default";

    private static final String FOLDER = "/Server/Prefabs/Sproutwatch/";

    private record Entry(String label, String resource) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    static {
        add(DEFAULT, "Default Lawn", PenPrefab.RESOURCE);
        add("kweebec_nursery", "Kweebec Nursery", FOLDER + "kweebec_nursery.prefab.json");
        add("cobble_pasture", "Cobble Pasture", FOLDER + "cobble_pasture.prefab.json");
    }

    private static void add(String id, String label, String resource) {
        ENTRIES.put(id, new Entry(label, resource));
    }

    private PenPrefabCatalog() {}

    /** Immutable, default first, in declaration order. */
    public static List<String> names() {
        return List.copyOf(ENTRIES.keySet());
    }

    /** Trimmed, lowercase; "" for null or blank. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean contains(String name) {
        return ENTRIES.containsKey(normalize(name));
    }

    /** Classpath resource for the named prefab, or the default's when the name is unknown. */
    public static String resourceFor(String name) {
        Entry entry = ENTRIES.get(normalize(name));
        return (entry != null ? entry : ENTRIES.get(DEFAULT)).resource();
    }

    /** Display label for the named prefab ("Default Lawn"), or the default's when the name is unknown. */
    public static String labelFor(String name) {
        Entry entry = ENTRIES.get(normalize(name));
        return (entry != null ? entry : ENTRIES.get(DEFAULT)).label();
    }
}
