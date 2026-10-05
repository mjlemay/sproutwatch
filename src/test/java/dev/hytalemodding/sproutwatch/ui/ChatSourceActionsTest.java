package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Twitch channel, start, stop, Begin and auto-start replies (YouTube settings are in ChatSourceActionsYouTubeTest). */
class ChatSourceActionsTest {

    @Test void setChannelWhenStoppedSavesAndTellsHowToStart() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Channel set to #streamer. Run /sproutwatch start to begin.", chatSources.setChannel("#Streamer!"));
        assertEquals("streamer", host.config.getTwitchChannel());
        assertEquals(1, host.saveCalls);
        assertEquals(0, host.startCalls);
    }

    @Test void setChannelWhileRunningRestartsListener() {
        FakeHost host = new FakeHost();
        host.running = true;
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Channel set to #streamer; listener restarted.", chatSources.setChannel("streamer"));
        assertEquals(1, host.startCalls);
        host.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(host.startError, chatSources.setChannel("other"));
        assertEquals("other", host.config.getTwitchChannel(), "the channel is saved even when the restart is refused");
        assertEquals(2, host.saveCalls);
    }

    @Test void setChannelRejectsInvalidInput() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Invalid channel name.", chatSources.setChannel("!!!"));
        assertEquals("Invalid channel name.", chatSources.setChannel(null));
        assertEquals(0, host.saveCalls);
    }

    @Test void startListenerPassesRefusalThrough() {
        FakeHost host = new FakeHost();
        host.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals(host.startError, new ChatSourceActions(host).startListener());
    }

    @Test void startAndStopWording() {
        FakeHost host = new FakeHost();
        host.config.setTwitchChannel("streamer");
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", chatSources.startListener());
        assertTrue(host.running);
        assertEquals("Sproutwatch stopped.", chatSources.stopListener());
        assertEquals("Sproutwatch was not running.", chatSources.stopListener());
        assertEquals(2, host.stopCalls);
    }

    @Test void beginStartsTheListenerOrReportsWhyNot() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        host.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(Optional.of(host.startError), chatSources.begin());
        host.startError = null;
        assertEquals(Optional.empty(), chatSources.begin());
        assertEquals(2, host.startCalls, "the failed attempt and the successful one");
        assertTrue(host.running);
        assertEquals(Optional.empty(), chatSources.begin(), "already running: nothing to start, just close");
        assertEquals(2, host.startCalls);
    }

    @Test void autoStartTogglesConfigAndSaves() {
        FakeHost host = new FakeHost();
        ChatSourceActions chatSources = new ChatSourceActions(host);
        assertEquals("Auto-start on boot is now on: the listener reconnects when the world loads.", chatSources.setAutoStart(true));
        assertTrue(host.config.isAutoStartOnBoot());
        assertEquals("Auto-start on boot is now off: press Start listener after each launch.", chatSources.setAutoStart(false));
        assertFalse(host.config.isAutoStartOnBoot());
        assertEquals(2, host.saveCalls);
    }
}
