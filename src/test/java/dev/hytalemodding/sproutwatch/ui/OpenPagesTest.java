package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OpenPagesTest {

    static final class FakePage implements OpenPages.Page {
        int refreshes;
        boolean alive = true;
        boolean explode;

        @Override public boolean refreshStatus() {
            refreshes++;
            if (explode) throw new IllegalStateException("boom");
            return alive;
        }
    }

    private static OpenPages pages() {
        return new OpenPages(Logger.getLogger("OpenPagesTest"));
    }

    @Test void registerAndRefreshDispatchToEveryPage() {
        OpenPages openPages = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage();
        openPages.register(a, pa);
        openPages.register(b, pb);
        assertEquals(2, openPages.size());
        assertTrue(openPages.isOpen(a));
        assertFalse(openPages.isOpen(UUID.randomUUID()));
        openPages.refreshAll();
        openPages.refreshAll();
        assertEquals(2, pa.refreshes);
        assertEquals(2, pb.refreshes);
    }

    @Test void registeringAgainReplacesThePage() {
        OpenPages openPages = pages();
        UUID a = UUID.randomUUID();
        FakePage old = new FakePage(), fresh = new FakePage();
        openPages.register(a, old);
        openPages.register(a, fresh);
        assertEquals(1, openPages.size());
        openPages.refreshAll();
        assertEquals(0, old.refreshes);
        assertEquals(1, fresh.refreshes);
    }

    @Test void forgetDropsByUuidAndByInstance() {
        OpenPages openPages = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage(), other = new FakePage();
        openPages.register(a, pa);
        openPages.register(b, pb);
        openPages.forget(a);
        assertFalse(openPages.isOpen(a));
        openPages.forget(b, other);
        assertTrue(openPages.isOpen(b), "a different instance must not evict the registered page");
        openPages.forget(b, pb);
        assertFalse(openPages.isOpen(b));
        assertEquals(0, openPages.size());
        openPages.forget(UUID.randomUUID());
    }

    @Test void refreshDropsPagesThatReportDead() {
        OpenPages openPages = pages();
        UUID a = UUID.randomUUID();
        FakePage pa = new FakePage();
        pa.alive = false;
        openPages.register(a, pa);
        openPages.refreshAll();
        assertEquals(0, openPages.size());
        openPages.refreshAll();
        assertEquals(1, pa.refreshes, "a dropped page is never refreshed again");
    }

    @Test void refreshSurvivesAThrowingPageAndDropsIt() {
        OpenPages openPages = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage bad = new FakePage(), good = new FakePage();
        bad.explode = true;
        openPages.register(a, bad);
        openPages.register(b, good);
        assertDoesNotThrow(openPages::refreshAll);
        assertEquals(1, good.refreshes);
        assertFalse(openPages.isOpen(a));
        assertTrue(openPages.isOpen(b));
    }
}
