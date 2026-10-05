package dev.hytalemodding.sproutwatch;

import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.SproutQueue;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import dev.hytalemodding.sproutwatch.youtube.QuotaPacer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Start/stop orchestration and the locking rule: status reads never take the listener lock, so a
 * source's state hook (or any other thread) can read status while stopListener is joining sources.
 */
class ListenerControllerTest {

    private static final long STATUS_READ_TIMEOUT_MILLIS = 500;

    private final SproutwatchConfig config = new SproutwatchConfigAccess().fresh();
    private final ChatRoster roster = new ChatRoster(config::ignoredViewers, config::getQueueCommand, new SproutQueue());
    private final DisplayNames displayNames = new DisplayNames();
    /** Every hook call and source start/stop, in order. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final RecordingHooks hooks = new RecordingHooks();
    private final List<ChatSource> nextSources = new ArrayList<>();
    private final ListenerController controller = new ListenerController(() -> config, currentConfig -> List.copyOf(nextSources),
        roster, displayNames, hooks, Logger.getLogger("ListenerControllerTest"));
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void shutDownThreads() {
        threads.shutdownNow();
    }

    private void readyToStart() {
        config.setTwitchChannel("streamer");
        config.setPen("world-uuid", 0, 64, 0, 10, 5, 10);
    }

    // ---- locking rule --------------------------------------------------------------------------

    @Test void statusReadsReturnWhileStopListenerIsBlockedInsideASourceStop() throws Exception {
        readyToStart();
        CountDownLatch insideStop = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        FakeSource blocking = new FakeSource("blocking") {
            @Override public void stop() {
                super.stop();
                insideStop.countDown();
                awaitQuietly(releaseStop);   // stands in for joining the source thread
            }
        };
        nextSources.add(blocking);
        assertNull(controller.startListener());

        Future<Boolean> stopping = threads.submit(controller::stopListener);
        assertTrue(insideStop.await(2, TimeUnit.SECONDS), "stopListener reached the source's stop()");
        try {
            assertReturnsPromptly("listenerState", controller::listenerState);
            assertReturnsPromptly("listenerRunning", controller::listenerRunning);
            assertReturnsPromptly("sourceStates", controller::sourceStates);
            assertReturnsPromptly("youTubeStatus", controller::youTubeStatus);
            assertReturnsPromptly("twitchClient", controller::twitchClient);
        } finally {
            releaseStop.countDown();
        }
        assertTrue(stopping.get(2, TimeUnit.SECONDS));
    }

    @Test void stateHookReadingStatusFromTheSourceThreadDuringStopDoesNotDeadlock() throws Exception {
        readyToStart();
        List<String> statesSeenByHook = Collections.synchronizedList(new ArrayList<>());
        hooks.onStatusChanged = () -> statesSeenByHook.add(controller.listenerState());
        FakeSource threaded = new FakeSource("threaded") {
            @Override public void stop() {
                super.stop();
                // Like a real source: its own thread fires the state hook, and stop() joins that thread.
                Thread sourceThread = new Thread(this::fireStateChange, "fake-source");
                sourceThread.start();
                try {
                    sourceThread.join();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        nextSources.add(threaded);
        assertNull(controller.startListener());

        Future<Boolean> stopping = threads.submit(controller::stopListener);
        assertTrue(stopping.get(2, TimeUnit.SECONDS), "stopListener finished; the hook did not wait on the lock");
        assertEquals(List.of("stopped"), statesSeenByHook);
    }

    // ---- start ---------------------------------------------------------------------------------

    @Test void refusedStartLeavesNothingRunning() {
        readyToStart();
        nextSources.add(new FakeSource("first"));
        assertNull(controller.startListener());
        assertTrue(controller.listenerRunning());

        config.setPen("", 0, 0, 0, 0, 0, 0);
        assertEquals("No pen placed yet. Stand where you want it and run /sproutwatch place.", controller.startListener());
        assertFalse(controller.listenerRunning());
        assertEquals(List.of("stop ticker", "youTube stopped", "start first", "sweep", "start ticker",
            "stop first", "stop ticker", "youTube stopped"), events);
    }

    @Test void nothingToStartReasonIsReturnedBeforeAnySourceIsBuilt() {
        config.setTwitchChannel("");
        nextSources.add(new FakeSource("unused"));
        assertEquals(config.nothingToStartReason(), controller.startListener());
        assertNotNull(config.nothingToStartReason());
        assertFalse(controller.listenerRunning());
        assertEquals(List.of("stop ticker", "youTube stopped"), events, "the always-stop-first still ran");
    }

    @Test void oneSourceFailingToStartDoesNotBlockTheOther() {
        readyToStart();
        nextSources.add(new FakeSource("broken") {
            @Override public void start() { throw new IllegalStateException("secret-ish detail"); }
        });
        FakeSource working = new FakeSource("working");
        nextSources.add(working);
        assertNull(controller.startListener());
        assertTrue(controller.listenerRunning());
        assertTrue(working.running);
        assertEquals(List.of("stop ticker", "youTube stopped", "start working", "sweep", "start ticker"), events);

        assertTrue(controller.stopListener());
        assertEquals("stop working", events.get(5), "only the started source is stopped");
    }

    @Test void noSourceCouldStartWhenAllFail() {
        readyToStart();
        nextSources.add(new FakeSource("broken") {
            @Override public void start() { throw new IllegalStateException(); }
        });
        assertEquals("No chat source could start (see the server log).", controller.startListener());
        assertFalse(controller.listenerRunning());
        assertFalse(controller.stopListener(), "nothing was recorded as started");
        assertFalse(events.contains("start ticker"));
        assertFalse(events.contains("sweep"));
    }

    @Test void startWiresTheStateHookToStatusChanged() {
        readyToStart();
        FakeSource source = new FakeSource("hooked");
        nextSources.add(source);
        assertNull(controller.startListener());
        source.fireStateChange();
        assertEquals(1, hooks.statusChangedCalls);
    }

    @Test void startStopsThePreviousRunFirst() {
        readyToStart();
        FakeSource first = new FakeSource("first");
        nextSources.add(first);
        assertNull(controller.startListener());
        nextSources.clear();
        nextSources.add(new FakeSource("second"));
        assertNull(controller.startListener());
        assertFalse(first.running);
        assertEquals(List.of("stop ticker", "youTube stopped", "start first", "sweep", "start ticker",
            "stop first", "stop ticker", "youTube stopped",
            "start second", "sweep", "start ticker"), events);
    }

    // ---- stop ----------------------------------------------------------------------------------

    @Test void stopReturnsFalseWhenNothingWasRunning() {
        assertFalse(controller.stopListener());
        assertEquals(List.of("stop ticker", "youTube stopped"), events);
    }

    @Test void stopReturnsTrueEvenWhenEverySourceEndedOnItsOwn() {
        readyToStart();
        FakeSource ended = new FakeSource("ended");
        nextSources.add(ended);
        assertNull(controller.startListener());
        ended.running = false;   // chat ended
        assertFalse(controller.listenerRunning());
        assertTrue(controller.stopListener());
    }

    @Test void sourcesAreStoppedBeforeTheRosterAndNamesAreCleared() {
        readyToStart();
        List<String> seenDuringStop = new ArrayList<>();
        nextSources.add(new FakeSource("producer") {
            @Override public void stop() {
                super.stop();
                seenDuringStop.add("roster " + roster.size());
                seenDuringStop.add("name " + displayNames.nameFor("yt:channel"));
            }
        });
        assertNull(controller.startListener());   // its stop-first clears the roster, so fill it after
        roster.addGuest("guest", 0);
        displayNames.put("yt:channel", "Viewer");
        assertTrue(controller.stopListener());
        assertEquals(List.of("roster 1", "name Viewer"), seenDuringStop);
        assertEquals(0, roster.size());
        assertNotEquals("Viewer", displayNames.nameFor("yt:channel"));
        assertEquals(List.of("stop ticker", "youTube stopped", "start producer", "sweep", "start ticker",
            "stop producer", "stop ticker", "youTube stopped"), events);
    }

    @Test void stopEmptiesTheSourcesBeforeStoppingThem() {
        readyToStart();
        List<Boolean> runningSeenDuringStop = new ArrayList<>();
        nextSources.add(new FakeSource("watched") {
            @Override public void stop() {
                runningSeenDuringStop.add(controller.listenerRunning());   // still running itself, but already swapped out
                super.stop();
            }
        });
        assertNull(controller.startListener());
        assertTrue(controller.stopListener());
        assertEquals(List.of(false), runningSeenDuringStop);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private <T> void assertReturnsPromptly(String name, Callable<T> statusRead) throws Exception {
        Future<T> result = threads.submit(statusRead);
        try {
            result.get(STATUS_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            result.cancel(true);
            fail(name + " blocked while stopListener was inside a source's stop() (it must not take the listener lock)");
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private class FakeSource implements ChatSource {
        private final String name;
        volatile boolean running;
        private volatile String state = "idle";
        private volatile Runnable onStateChange = () -> {};

        FakeSource(String name) { this.name = name; }

        @Override public void start() {
            running = true;
            state = "connected";
            events.add("start " + name);
        }

        @Override public void stop() {
            running = false;
            state = "stopped";
            events.add("stop " + name);
        }

        @Override public boolean isRunning() { return running; }
        @Override public String getState() { return state; }
        @Override public void setOnStateChange(Runnable hook) { onStateChange = hook; }

        void fireStateChange() { onStateChange.run(); }
    }

    private class RecordingHooks implements ListenerController.Hooks {
        volatile Runnable onStatusChanged = () -> {};
        volatile int statusChangedCalls;

        @Override public void startTicker() { events.add("start ticker"); }
        @Override public void stopTicker() { events.add("stop ticker"); }
        @Override public void youTubeSourceFailedToStart() { events.add("youTube failed"); }
        @Override public void youTubeStopped() { events.add("youTube stopped"); }
        @Override public void sweepPenWorld(SproutwatchConfig currentConfig) { events.add("sweep"); }
        @Override public QuotaPacer currentYouTubePacer() { return null; }

        @Override public void statusChanged() {
            statusChangedCalls++;
            onStatusChanged.run();
        }
    }
}
