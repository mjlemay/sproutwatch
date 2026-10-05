package dev.hytalemodding.sproutwatch.chat;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The run, state and announce bookkeeping shared by every {@link ChatSource}.
 *
 * Each {@link #begin} starts a new run and returns its generation; {@link #end} bumps the
 * generation again. A background thread passes its generation to {@link #live}, {@link #setState}
 * and {@link #finish}, so a slow request that finishes after Stop (or after a later Start) can no
 * longer change state or touch the roster.
 *
 * Announcing logs the new state (when a log prefix is given) and runs the on-state-change hook,
 * always outside the lock and never throwing: a broken hook or logger must not kill a listener
 * thread or skip a stop's join.
 */
public final class SourceLifecycle {

    private final Logger logger;
    private final String stateLogPrefix;

    private final Object stateLock = new Object();
    private volatile String state;
    private volatile Runnable onStateChange;
    private volatile boolean running;
    /** Bumped per begin and end; a run whose generation is stale may no longer touch state. */
    private long currentGeneration;

    /**
     * @param logger         receives the state log lines and hook failures
     * @param stateLogPrefix nullable: when set, every announced state is logged at INFO as
     *                       {@code stateLogPrefix + state}; when null, state changes are not logged
     * @param initialState   the state before the first {@link #begin}
     */
    public SourceLifecycle(Logger logger, String stateLogPrefix, String initialState) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateLogPrefix = stateLogPrefix;
        this.state = Objects.requireNonNull(initialState, "initialState");
    }

    public void setOnStateChange(Runnable hook) {
        onStateChange = hook;
    }

    /**
     * Starts a new run in {@code initialState}; announces when the state changed.
     *
     * @return the run's generation
     */
    public long begin(String initialState) {
        long generation;
        boolean changed;
        synchronized (stateLock) {
            generation = ++currentGeneration;
            running = true;
            changed = !initialState.equals(state);
            state = initialState;
        }
        if (changed) announce(initialState);
        return generation;
    }

    /**
     * Ends the current run: no older generation is live afterward, and the state becomes
     * {@code stoppedState} (also replacing a terminal reason). {@code beforeAnnounce} (for example
     * interrupting and joining the listener thread) runs after the state flip and before the
     * announcement, outside the lock, so a slow hook cannot delay it.
     */
    public void end(String stoppedState, Runnable beforeAnnounce) {
        boolean changed;
        synchronized (stateLock) {
            currentGeneration++;
            running = false;
            changed = !stoppedState.equals(state);
            state = stoppedState;
        }
        try {
            beforeAnnounce.run();
        } finally {
            if (changed) announce(stoppedState);
        }
    }

    /** True while run {@code generation} is the current one and has not ended. */
    public boolean live(long generation) {
        synchronized (stateLock) {
            return running && generation == currentGeneration;
        }
    }

    /**
     * Applies {@code next} if run {@code generation} is still live; announces on change.
     *
     * @return true when the state changed
     */
    public boolean setState(long generation, String next) {
        synchronized (stateLock) {
            if (generation != currentGeneration || !running || next.equals(state)) return false;
            state = next;
        }
        announce(next);
        return true;
    }

    /**
     * Terminal: run {@code generation} ends on its own with {@code reason} as its visible state,
     * until the next {@link #end} replaces it.
     *
     * @return true when the run was live and has now ended
     */
    public boolean finish(long generation, String reason) {
        synchronized (stateLock) {
            if (generation != currentGeneration || !running) return false;
            running = false;
            if (reason.equals(state)) return true;
            state = reason;
        }
        announce(reason);
        return true;
    }

    public String state() {
        return state;
    }

    public boolean running() {
        return running;
    }

    /** Logs without ever throwing (a broken logger must not kill a listener or skip a stop's join). */
    public void safeLog(Level level, String message) {
        try {
            logger.log(level, message);
        } catch (RuntimeException ignored) {
            // nothing sensible left to do
        }
    }

    private void announce(String next) {
        if (stateLogPrefix != null) safeLog(Level.INFO, stateLogPrefix + next);
        Runnable hook = onStateChange;
        if (hook == null) return;
        try {
            hook.run();
        } catch (RuntimeException exception) {
            try {
                logger.log(Level.WARNING, "Sproutwatch state-change hook failed", exception);
            } catch (RuntimeException ignored) {
                // a broken logger must not break the state machine
            }
        }
    }
}
