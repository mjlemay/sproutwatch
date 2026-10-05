package dev.hytalemodding.sproutwatch.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SourceLifecycleTest {

    private final List<String> logLines = new CopyOnWriteArrayList<>();

    private Logger recordingLogger(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                logLines.add(record.getLevel() + " " + record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    @Test
    void staleGenerationIsIgnored() {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-1"), null, "stopped");
        List<String> announced = new CopyOnWriteArrayList<>();
        lifecycle.setOnStateChange(() -> announced.add(lifecycle.state()));

        long first = lifecycle.begin("connecting");
        lifecycle.end("stopped", () -> {});
        long second = lifecycle.begin("connecting");

        assertNotEquals(first, second);
        assertFalse(lifecycle.live(first));
        assertTrue(lifecycle.live(second));
        assertFalse(lifecycle.setState(first, "connected"), "a stale run may not change state");
        assertFalse(lifecycle.finish(first, "chat ended"), "a stale run may not end the new one");
        assertEquals("connecting", lifecycle.state());
        assertTrue(lifecycle.running());
        assertTrue(lifecycle.setState(second, "connected"));
        assertFalse(lifecycle.setState(second, "connected"), "unchanged state is not announced again");
        assertEquals(List.of("connecting", "stopped", "connecting", "connected"), announced);
    }

    @Test
    void endedRunIsNotLiveAndCannotChangeState() {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-2"), null, "stopped");
        long generation = lifecycle.begin("connecting");
        lifecycle.end("stopped", () -> {});

        assertFalse(lifecycle.live(generation));
        assertFalse(lifecycle.running());
        assertFalse(lifecycle.setState(generation, "reconnecting"));
        assertEquals("stopped", lifecycle.state());
    }

    @Test
    void finishKeepsTheReasonUntilEndReplacesItWithStopped() {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-3"), null, "stopped");
        List<String> announced = new CopyOnWriteArrayList<>();
        lifecycle.setOnStateChange(() -> announced.add(lifecycle.state()));
        long generation = lifecycle.begin("connecting");

        assertTrue(lifecycle.finish(generation, "chat ended"));
        assertFalse(lifecycle.running());
        assertFalse(lifecycle.live(generation));
        assertEquals("chat ended", lifecycle.state());
        assertFalse(lifecycle.setState(generation, "reconnecting"), "a finished run may not change state");
        assertEquals("chat ended", lifecycle.state());

        lifecycle.end("stopped", () -> {});
        assertEquals("stopped", lifecycle.state());
        assertEquals(List.of("connecting", "chat ended", "stopped"), announced);
    }

    @Test
    void endRunsItsStepBeforeAnnouncingAndSkipsTheAnnouncementWhenUnchanged() {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-4"), null, "stopped");
        List<String> events = new CopyOnWriteArrayList<>();
        lifecycle.setOnStateChange(() -> events.add("announced " + lifecycle.state()));
        lifecycle.begin("connecting");
        events.clear();

        lifecycle.end("stopped", () -> events.add("joined while " + lifecycle.state()));
        lifecycle.end("stopped", () -> events.add("joined again"));

        assertEquals(List.of("joined while stopped", "announced stopped", "joined again"), events);
    }

    @Test
    void stateLogPrefixLogsEveryAnnouncedStateAndNullLogsNone() {
        SourceLifecycle logged = new SourceLifecycle(recordingLogger("source-lifecycle-5"), "Prefix: ", "stopped");
        long generation = logged.begin("connecting");
        logged.setState(generation, "connected");
        logged.end("stopped", () -> {});
        assertEquals(List.of("INFO Prefix: connecting", "INFO Prefix: connected", "INFO Prefix: stopped"), logLines);

        logLines.clear();
        SourceLifecycle quiet = new SourceLifecycle(recordingLogger("source-lifecycle-6"), null, "stopped");
        quiet.begin("connecting");
        quiet.end("stopped", () -> {});
        assertEquals(List.of(), logLines);
    }

    @Test
    void hookExceptionsAreSwallowedAndLogged() {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-7"), null, "stopped");
        lifecycle.setOnStateChange(() -> { throw new IllegalStateException("broken hook"); });

        long generation = assertDoesNotThrow(() -> lifecycle.begin("connecting"));
        assertDoesNotThrow(() -> lifecycle.setState(generation, "connected"));
        assertDoesNotThrow(() -> lifecycle.end("stopped", () -> {}));

        assertEquals("stopped", lifecycle.state());
        assertEquals(3, logLines.stream().filter(line -> line.equals("WARNING Sproutwatch state-change hook failed")).count(),
            logLines.toString());
    }

    @Test
    void brokenLoggerNeverThrows() {
        Logger broken = Logger.getLogger("source-lifecycle-8");
        broken.setUseParentHandlers(false);
        broken.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { throw new IllegalStateException("broken logger"); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        SourceLifecycle lifecycle = new SourceLifecycle(broken, "Prefix: ", "stopped");
        lifecycle.setOnStateChange(() -> { throw new IllegalStateException("broken hook"); });

        assertDoesNotThrow(() -> lifecycle.begin("connecting"));
        assertDoesNotThrow(() -> lifecycle.safeLog(Level.WARNING, "anything"));
        assertDoesNotThrow(() -> lifecycle.end("stopped", () -> {}));
        assertEquals("stopped", lifecycle.state());
    }

    @Test
    @Timeout(5)
    void hookRunsOutsideTheLock() throws Exception {
        SourceLifecycle lifecycle = new SourceLifecycle(recordingLogger("source-lifecycle-9"), null, "stopped");
        // The hook asks another thread to read through the lock and waits for it. Were the hook
        // run while the lock is held, the reader would block until the hook returned, so it would
        // not be done inside the hook.
        CompletableFuture<Boolean> seenFromOtherThread = new CompletableFuture<>();
        AtomicBoolean doneInsideHook = new AtomicBoolean();
        long[] generation = new long[1];
        lifecycle.setOnStateChange(() -> {
            if (!"connected".equals(lifecycle.state())) return;
            Thread reader = new Thread(() -> seenFromOtherThread.complete(lifecycle.live(generation[0])));
            reader.start();
            try {
                reader.join(1_000);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            doneInsideHook.set(seenFromOtherThread.isDone());
        });
        generation[0] = lifecycle.begin("connecting");
        lifecycle.setState(generation[0], "connected");

        assertTrue(doneInsideHook.get(), "the reader finished while the hook was still running");
        assertTrue(seenFromOtherThread.get(1, TimeUnit.SECONDS));
    }
}
