package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Filter mode and Twitch allow/ignore entries (YouTube entries are in ViewerListActionsYouTubeTest). */
class ViewerListActionsTest {

    @Test void allowAddRemoveNormalizesAndSaves() {
        FakeHost host = new FakeHost();
        ViewerListActions viewerLists = new ViewerListActions(host);
        assertEquals("Added alice to the allow list.", viewerLists.addAllow("@Alice"));
        assertEquals("alice is already on the allow list.", viewerLists.addAllow("alice"));
        assertEquals(Set.of("alice"), host.config.allowedViewers());
        assertEquals("Removed alice from the allow list.", viewerLists.removeAllow("ALICE"));
        assertEquals("alice is not on the allow list.", viewerLists.removeAllow("alice"));
        assertEquals(2, host.saveCalls, "saved once per successful change");
    }

    @Test void ignoreAddPartsTheViewerAndRemoveRestores() {
        FakeHost host = new FakeHost();
        ViewerListActions viewerLists = new ViewerListActions(host);
        host.roster.apply(new RosterEvent.Chat("spammer", "!sprout"), 1L);
        assertEquals(1, host.roster.size());
        assertEquals(1, host.roster.queue().size());
        assertEquals("Added spammer to the ignore list.", viewerLists.addIgnore("Spammer"));
        assertEquals(0, host.roster.size(), "an ignored viewer leaves the roster at once");
        assertEquals(0, host.roster.queue().size());
        assertTrue(host.config.ignoredViewers().contains("spammer"));
        assertEquals("spammer is already on the ignore list.", viewerLists.addIgnore("spammer"));
        assertEquals("Removed spammer from the ignore list.", viewerLists.removeIgnore("spammer"));
        assertEquals("spammer is not on the ignore list.", viewerLists.removeIgnore("spammer"));
        assertEquals(2, host.saveCalls);
    }

    @Test void invalidLoginsAreRejectedEverywhere() {
        FakeHost host = new FakeHost();
        ViewerListActions viewerLists = new ViewerListActions(host);
        for (String bad : new String[]{null, "", "   ", "!!!"}) {
            assertEquals("Invalid user name.", viewerLists.addAllow(bad));
            assertEquals("Invalid user name.", viewerLists.removeAllow(bad));
            assertEquals("Invalid user name.", viewerLists.addIgnore(bad));
            assertEquals("Invalid user name.", viewerLists.removeIgnore(bad));
        }
        assertEquals(0, host.saveCalls);
    }

    @Test void setFilterSwitchesModeAndSaves() {
        FakeHost host = new FakeHost();
        SproutwatchActions actions = new SproutwatchActions(host);
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (the list is empty, so nobody until you add someone).",
            actions.viewerLists().setFilter(true));
        assertTrue(host.config.isAllowMode());
        actions.viewerLists().addAllow("alice");
        assertEquals("Filter is now the allow list: only listed viewers get a sprout (1 listed).", actions.viewerLists().setFilter(true));
        assertEquals("Filter is now the ignore list: everyone in chat except 3 ignored.", actions.viewerLists().setFilter(false));
        assertFalse(host.config.isAllowMode());
        assertEquals(4, host.saveCalls, "three filter changes and one addAllow");
        actions.viewerLists().setFilter(true);
        assertEquals("Filter: allow list (1 allowed)", actions.snapshot().filterLine());
    }
}
