package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StatusPusherTest {

    /** Queues tasks until runQueued(); records whether a task is running inside execute's drain. */
    static final class FakeWorld implements StatusPusher.WorldExecutor {
        boolean inThread;
        boolean unloading;
        boolean executing;
        int executeCalls;
        final List<Runnable> queued = new ArrayList<>();

        @Override public boolean isInThread() {
            return inThread;
        }

        @Override public void execute(Runnable task) {
            executeCalls++;
            if (unloading) throw new IllegalStateException("world unloading");
            queued.add(task);
        }

        void runQueued() {
            List<Runnable> tasks = new ArrayList<>(queued);
            queued.clear();
            executing = true;
            try {
                for (Runnable task : tasks) task.run();
            } finally {
                executing = false;
            }
        }
    }

    /** Records what reached it and whether the world was executing a queued task at the time. */
    static final class FakeSink implements StatusPusher.PageSink {
        final FakeWorld world;
        final List<PageState> sentStates = new ArrayList<>();
        final List<Boolean> sentWhileExecuting = new ArrayList<>();
        final List<Thread> sentOnThreads = new ArrayList<>();
        int rebuilds;
        boolean explode;

        FakeSink(FakeWorld world) {
            this.world = world;
        }

        @Override public void rebuild() {
            if (explode) throw new IllegalStateException("rebuild boom");
            rebuilds++;
        }

        @Override public void sendState(PageState state) {
            if (explode) throw new IllegalStateException("send boom");
            sentStates.add(state);
            sentWhileExecuting.add(world.executing);
            sentOnThreads.add(Thread.currentThread());
        }

        int calls() {
            return sentStates.size() + rebuilds;
        }
    }

    static final class RecordingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() {
        }

        @Override public void close() {
        }
    }

    private FakeWorld world;
    private FakeSink sink;
    private RecordingHandler logged;
    private StatusPusher pusher;

    @BeforeEach void setUp() {
        world = new FakeWorld();
        sink = new FakeSink(world);
        logged = new RecordingHandler();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(logged);
        pusher = new StatusPusher(sink, logger);
    }

    private static PageState state(String label, String listsKey) {
        return new PageState(Collections.nCopies(PageState.SELECTORS.size(), label), "ValueGood", true, RunButton.START, listsKey);
    }

    @Test void anUnchangedStateTouchesNeitherTheWorldNorTheSink() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = true;

        assertTrue(pusher.refresh(() -> state("x", "lists"), world));

        assertEquals(0, world.executeCalls);
        assertEquals(0, sink.calls());
        assertTrue(logged.records.isEmpty());
    }

    @Test void changedLabelsOnTheWorldThreadAreSentImmediatelyOnTheCallingThread() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = true;
        PageState changed = state("y", "lists");

        assertTrue(pusher.refresh(() -> changed, world));

        assertEquals(0, world.executeCalls);
        assertEquals(List.of(changed), sink.sentStates);
        assertEquals(List.of(Thread.currentThread()), sink.sentOnThreads);
        assertEquals(0, sink.rebuilds);
        // Recorded as shown: the same state again is gated.
        assertTrue(pusher.refresh(() -> state("y", "lists"), world));
        assertEquals(1, sink.calls());
    }

    @Test void changedLabelsOffTheWorldThreadWaitForTheQueuedTask() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = false;
        PageState changed = state("y", "lists");

        assertTrue(pusher.refresh(() -> changed, world));

        assertEquals(1, world.executeCalls);
        assertEquals(0, sink.calls(), "nothing may reach the sink before the world thread runs the task");
        world.runQueued();
        assertEquals(List.of(changed), sink.sentStates);
        assertEquals(List.of(true), sink.sentWhileExecuting);
        assertEquals(0, sink.rebuilds);
    }

    @Test void aChangedListsKeyRebuildsInsteadOfSending() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = true;

        assertTrue(pusher.refresh(() -> state("x", "lists,new"), world));

        assertEquals(1, sink.rebuilds);
        assertTrue(sink.sentStates.isEmpty());
    }

    @Test void thePushBeforeAnyBuildRebuilds() {
        world.inThread = true;

        assertTrue(pusher.refresh(() -> state("x", "lists"), world));

        assertEquals(1, sink.rebuilds);
        assertTrue(sink.sentStates.isEmpty());
    }

    @Test void dismissedBeforeTheQueuedTaskRunsTheTaskDoesNothing() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = false;

        assertTrue(pusher.refresh(() -> state("y", "lists"), world));
        assertFalse(pusher.isDismissed());
        pusher.dismiss();
        assertTrue(pusher.isDismissed());
        world.runQueued();

        assertEquals(0, sink.calls());
    }

    @Test void aSinkThatThrowsDuringTheQueuedPushIsLoggedAndThePageStays() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = false;
        sink.explode = true;

        assertTrue(pusher.refresh(() -> state("y", "lists"), world));
        assertDoesNotThrow(world::runQueued);

        assertEquals(1, logged.records.size());
        assertEquals("Sproutwatch settings page push failed", logged.records.get(0).getMessage());
        assertInstanceOf(IllegalStateException.class, logged.records.get(0).getThrown());
        // Not recorded as shown: the next refresh tries again.
        sink.explode = false;
        world.inThread = true;
        assertTrue(pusher.refresh(() -> state("y", "lists"), world));
        assertEquals(1, sink.sentStates.size());
    }

    @Test void anExecutorThatThrowsDropsThePage() {
        pusher.recordBuilt(state("x", "lists"));
        world.inThread = false;
        world.unloading = true;

        assertFalse(pusher.refresh(() -> state("y", "lists"), world));

        assertEquals(0, sink.calls());
        assertEquals(1, logged.records.size());
        assertEquals("Sproutwatch settings page refresh failed; dropping the page", logged.records.get(0).getMessage());
    }

    @Test void aStateThatFailsToBuildDropsThePage() {
        world.inThread = true;

        assertFalse(pusher.refresh(() -> { throw new IllegalStateException("snapshot boom"); }, world));

        assertEquals(0, world.executeCalls);
        assertEquals(0, sink.calls());
        assertEquals(1, logged.records.size());
    }
}
