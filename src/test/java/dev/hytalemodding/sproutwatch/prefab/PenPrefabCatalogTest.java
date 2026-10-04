package dev.hytalemodding.sproutwatch.prefab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenPrefabCatalogTest {

    @Test void namesListsDefaultFirst() {
        List<String> names = PenPrefabCatalog.names();
        assertEquals("default", names.get(0));
        assertEquals(1, names.size(), "only the bundled pen exists today; extend RESOURCES when adding one");
        assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
    }

    @Test void normalizeTrimsAndLowercases() {
        assertEquals("default", PenPrefabCatalog.normalize("  Default "));
        assertEquals("", PenPrefabCatalog.normalize(null));
        assertEquals("", PenPrefabCatalog.normalize("   "));
    }

    @Test void containsAndResourceForFallBackToDefault() {
        assertTrue(PenPrefabCatalog.contains("default"));
        assertFalse(PenPrefabCatalog.contains("castle"));
        assertFalse(PenPrefabCatalog.contains(null));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor("default"));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor("castle"));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor(null));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor(" DEFAULT "));
    }

    @Test void everyCatalogEntryIsBundledAndParses() throws Exception {
        for (String name : PenPrefabCatalog.names()) {
            String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(name));
            assertTrue(PenPrefab.parseBlocks(json).size() > 1000, name + " should be a full pen");
        }
    }
}
