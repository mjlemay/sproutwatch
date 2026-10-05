package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The status snapshot and /sproutwatch status text built by the holder. */
class SproutwatchActionsTest {

    @Test void snapshotReflectsHostAndConfig() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.config.setChair(true, 17, 65, 13);
        host.config.setPenFacing("south");
        host.config.addAllow("alice");
        host.config.addAllow("dave");
        host.config.addAllow("erin");
        host.roster.apply(new RosterEvent.Chat("bob", "!sprout"), 1L);
        host.roster.apply(new RosterEvent.Chat("frank", "!sprout"), 2L);
        host.roster.apply(new RosterEvent.Chat("grace", "hello"), 3L);
        host.roster.apply(new RosterEvent.Chat("heidi", "hello"), 4L);
        host.roster.apply(new RosterEvent.Chat("ivan", "hello"), 5L);
        host.registry.put(new PenRegistry.Entry("bob", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("frank", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("grace", null, -1, null, 0L, 0L));
        host.registry.put(new PenRegistry.Entry("heidi", null, -1, null, 0L, 0L));
        host.registry.retire("carol");
        host.running = true;
        host.acknowledged = true;
        host.tickerRunning = true;
        StatusSnapshot s = new SproutwatchActions(host).snapshot();
        assertEquals("connected to #streamer", s.listenerState());
        assertTrue(s.listenerRunning());
        assertTrue(s.feedAcknowledged());
        assertEquals("streamer", s.channel());
        // Every count is distinct so a positional swap in the 29-component constructor fails here.
        assertEquals(5, s.rosterSize());
        assertEquals(2, s.queueSize());
        assertEquals("!sprout", s.queueCommand());
        assertEquals(3, s.allowCount());
        assertEquals(4, s.penCount());
        assertEquals(30, s.cap());
        assertEquals(1, s.retiredCount());
        assertEquals(60, s.tickSeconds());
        assertEquals(300, s.graceSeconds());
        assertTrue(s.tickerRunning());
        assertTrue(s.persist());
        assertTrue(s.penSet());
        assertEquals(10, s.penX());
        assertEquals(64, s.penY());
        assertEquals(20, s.penZ());
        assertEquals(16, s.penSizeX());
        assertEquals(12, s.penSizeZ());
        assertEquals("south", s.penFacing());
        assertNull(s.penWorldName(), "the fake host never loads a world");
        assertEquals("11111111-2222-3333-4444-555555555555", s.penWorldId());
        assertTrue(s.chairSet());
        assertEquals(17, s.chairX());
        assertEquals(65, s.chairY());
        assertEquals(13, s.chairZ());
        assertEquals("default", s.prefabName());
    }

    @Test void statusReportMatchesCommandWording() {
        FakeHost host = new FakeHost();
        SproutwatchActions actions = new SproutwatchActions(host);
        assertEquals(String.join("\n",
            "Listener: stopped",
            "Channel: (unset)",
            "Seen in chat: 0 (chatters since Start)",
            "Queue: 0 waiting (typed !sprout in chat)",
            "Filter: ignore list (everyone in chat except 3 ignored)",
            "Pen: 0/30 sprouts, tick 60s, grace 300s, ticker stopped",
            "Persist: on (longest-gone replaced at the cap)",
            "Pen: not placed (run /sproutwatch place)"), actions.statusReport());

        host.config.setTwitchChannel("streamer");
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.running = true;
        host.registry.retire("carol");
        String running = actions.statusReport();
        assertTrue(running.contains("Listener: connected to #streamer\n"), running);
        assertTrue(running.contains("Channel: #streamer\n"), running);
        assertTrue(running.contains("Twitch JOIN/PART feed: OFF (Twitch did not grant membership; only viewers who chat will appear)\n"), running);
        assertTrue(running.contains("Retired (killed by a player): 1\n"), running);
        assertTrue(running.contains("Pen placed: 16x12 at 10,64,20 in an unloaded world (11111111-2222-3333-4444-555555555555), camera faces north\n"), running);
        assertTrue(running.endsWith("Chair: (none; use /sproutwatch camera)"), running);
    }

    @Test void snapshotLabelsForThePage() {
        FakeHost host = new FakeHost();
        SproutwatchActions actions = new SproutwatchActions(host);
        StatusSnapshot stopped = actions.snapshot();
        assertEquals("Twitch JOIN/PART feed: n/a (listener stopped)", stopped.feedLine());
        assertEquals("Listener stopped", stopped.runLabel());
        assertEquals("Place a pen to wrangle viewers", stopped.beginCaption());
        assertEquals("Persist on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced", stopped.persistLabel());
        assertEquals("Auto-start off: press Start listener after each launch", stopped.autoStartLabel());
        assertEquals(List.of("(unset)", "stopped", "n/a (listener stopped)", "YouTube off", "on (longest-gone replaced at the cap)",
            "0 (chatters since Start)", "0 waiting (typed !sprout in chat)", "ignore list (everyone in chat except 3 ignored)",
            "0/30 sprouts, tick 60s, grace 300s, ticker stopped", "0"), stopped.detailValues());
        assertEquals(StatusSnapshot.DETAIL_NAMES.size(), stopped.detailValues().size());
        assertEquals(StatusSnapshot.DETAIL_IDS.size(), stopped.detailValues().size());
        assertEquals("#ChannelValue", StatusSnapshot.DETAIL_IDS.get(0));
        assertEquals("#YouTubeQuotaValue", StatusSnapshot.DETAIL_IDS.get(3));
        assertEquals("#FilterValue", StatusSnapshot.DETAIL_IDS.get(7));
        assertEquals("ValuePlain", stopped.listenerTone());
        assertEquals("Chair: (no pen)", stopped.chairLine());
        assertEquals("Retired (killed by a player): 0", stopped.retiredLine());

        host.config.setTwitchChannel("streamer");
        host.config.setPersistSprouts(false);
        host.config.setAutoStartOnBoot(true);
        host.config.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        host.config.setChair(true, 17, 65, 13);
        host.running = true;
        host.acknowledged = true;
        StatusSnapshot running = actions.snapshot();
        assertEquals("Twitch JOIN/PART feed: on", running.feedLine());
        assertEquals("Listener running (connected to #streamer)", running.runLabel());
        assertEquals("Close and bring viewers to the pen.", running.beginCaption());
        assertEquals("Persist off: sprouts despawn 300s after their viewer leaves", running.persistLabel());
        assertEquals("Auto-start on: the listener reconnects when the world loads", running.autoStartLabel());
        assertEquals("Chair: 17,65,13", running.chairLine());
        assertEquals("#streamer", running.detailValues().get(0));
        assertEquals("connected to #streamer", running.detailValues().get(1));
        assertEquals("ValueGood", running.listenerTone());
        assertEquals("ValueWarn", StatusSnapshot.toneFor("connecting"));
        assertEquals("ValueBad", StatusSnapshot.toneFor("reconnecting"));
        assertEquals("ValuePlain", StatusSnapshot.toneFor("stopped"));
        assertEquals("start", StatusSnapshot.runButtonFor(false, "stopped"));
        assertEquals("connecting", StatusSnapshot.runButtonFor(true, "connecting"));
        assertEquals("connecting", StatusSnapshot.runButtonFor(true, "reconnecting"));
        assertEquals("stop", StatusSnapshot.runButtonFor(true, "connected to #streamer"));

        // The loaded-world branch needs a world name, which the fake host never supplies: build the record directly.
        StatusSnapshot loaded = new StatusSnapshot(
            "connected to #streamer", true, true,
            "streamer", 0, 0, "!sprout", false, 0, 3,
            0, 30, 0, 60, 300, 600, true,
            true, false,
            true, 10, 64, 20, 16, 12, "north",
            "Testr World", "11111111-2222-3333-4444-555555555555",
            true, 17, 65, 13,
            "default");
        assertEquals("Pen placed: 16x12 at 10,64,20 in Testr World, camera faces north", loaded.penPlacedLine());
    }
}
