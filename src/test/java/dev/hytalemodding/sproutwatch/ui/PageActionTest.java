package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageActionTest {

    private static final Path UI_SOURCES = Path.of("src/main/java/dev/hytalemodding/sproutwatch/ui");

    @Test void everyWireNameRoundTripsThroughFromWire() {
        for (PageAction action : PageAction.values()) {
            assertEquals(Optional.of(action), PageAction.fromWire(action.wireName()), action.name());
        }
    }

    @Test void wireNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (PageAction action : PageAction.values()) {
            assertTrue(seen.add(action.wireName()), "duplicate wire name " + action.wireName());
        }
    }

    @Test void unknownNullOrBlankWireNamesResolveToEmpty() {
        assertEquals(Optional.empty(), PageAction.fromWire(null));
        assertEquals(Optional.empty(), PageAction.fromWire(""));
        assertEquals(Optional.empty(), PageAction.fromWire("   "));
        assertEquals(Optional.empty(), PageAction.fromWire("bogus"));
        assertEquals(Optional.empty(), PageAction.fromWire("SaveChannel"));   // wire names are case-sensitive
        assertEquals(Optional.empty(), PageAction.fromWire("SAVE_CHANNEL"));   // the constant name is not a wire name
    }

    /** The client sends these strings back verbatim, so renaming one would break the page. */
    @Test void wireNamesMatchTheStringsTheClientSends() {
        Set<String> expected = Set.of(
            "tab", "begin",
            "saveChannel", "removeChannel", "setTwitchEnabled", "setYouTubeEnabled",
            "saveYouTubeHandle", "removeYouTubeHandle", "saveYouTubeKey", "removeYouTubeKey",
            "saveYouTubeVideo", "removeYouTubeVideo", "saveMax",
            "setFilter", "addAllow", "removeAllow", "addIgnore", "removeIgnore",
            "startListener", "stopListener", "setPersist", "setAutoStart", "saveInterval",
            "selectCreatures", "selectPrefab", "place", "clear");
        Set<String> actual = new HashSet<>();
        for (PageAction action : PageAction.values()) actual.add(action.wireName());
        assertEquals(expected, actual);
    }

    /** Every binding goes through PageAction: no action is bound as a bare string literal. */
    @Test void bindingSitesUseNoStringLiteralActions() throws IOException {
        for (String file : new String[] {"SettingsPanes.java", "SettingsTab.java"}) {
            String source = Files.readString(UI_SOURCES.resolve(file));
            assertFalse(source.contains("KEY_ACTION, \""), file + " binds an action as a string literal");
        }
    }
}
