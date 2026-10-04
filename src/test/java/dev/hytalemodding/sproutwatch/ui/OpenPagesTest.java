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
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage();
        p.register(a, pa);
        p.register(b, pb);
        assertEquals(2, p.size());
        assertTrue(p.isOpen(a));
        assertFalse(p.isOpen(UUID.randomUUID()));
        p.refreshAll();
        p.refreshAll();
        assertEquals(2, pa.refreshes);
        assertEquals(2, pb.refreshes);
    }

    @Test void registeringAgainReplacesThePage() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID();
        FakePage old = new FakePage(), fresh = new FakePage();
        p.register(a, old);
        p.register(a, fresh);
        assertEquals(1, p.size());
        p.refreshAll();
        assertEquals(0, old.refreshes);
        assertEquals(1, fresh.refreshes);
    }

    @Test void forgetDropsByUuidAndByInstance() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage(), other = new FakePage();
        p.register(a, pa);
        p.register(b, pb);
        p.forget(a);
        assertFalse(p.isOpen(a));
        p.forget(b, other);
        assertTrue(p.isOpen(b), "a different instance must not evict the registered page");
        p.forget(b, pb);
        assertFalse(p.isOpen(b));
        assertEquals(0, p.size());
        p.forget(UUID.randomUUID());
    }

    @Test void refreshDropsPagesThatReportDead() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID();
        FakePage pa = new FakePage();
        pa.alive = false;
        p.register(a, pa);
        p.refreshAll();
        assertEquals(0, p.size());
        p.refreshAll();
        assertEquals(1, pa.refreshes, "a dropped page is never refreshed again");
    }

    @Test void refreshSurvivesAThrowingPageAndDropsIt() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage bad = new FakePage(), good = new FakePage();
        bad.explode = true;
        p.register(a, bad);
        p.register(b, good);
        assertDoesNotThrow(p::refreshAll);
        assertEquals(1, good.refreshes);
        assertFalse(p.isOpen(a));
        assertTrue(p.isOpen(b));
    }
}
