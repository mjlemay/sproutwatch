package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsTabTest {

    @Test void tabsInDisplayOrderWithTheirSelectors() {
        assertArrayEquals(new SettingsTab[] {SettingsTab.CONNECT, SettingsTab.PEN, SettingsTab.DETAILS, SettingsTab.VIEWERS, SettingsTab.LISTENER},
            SettingsTab.values());
        assertEquals("#TabViewers", SettingsTab.VIEWERS.button);
        assertEquals("#ViewersTab", SettingsTab.VIEWERS.group);
        assertEquals("#TabConnect", SettingsTab.CONNECT.button);
        assertEquals("#DetailsTab", SettingsTab.DETAILS.group);
        assertEquals("#PenTab", SettingsTab.PEN.group);
    }

    @Test void parseAcceptsEnumNames() {
        assertEquals(SettingsTab.LISTENER, SettingsTab.parse("LISTENER", SettingsTab.CONNECT));
        assertEquals(SettingsTab.PEN, SettingsTab.parse("PEN", SettingsTab.CONNECT));
    }

    @Test void parseKeepsTheCurrentTabForNullOrUnknownNames() {
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse(null, SettingsTab.VIEWERS));
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse("Bogus", SettingsTab.VIEWERS));
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse("listener", SettingsTab.VIEWERS));
    }
}
