package dev.hytalemodding.sproutwatch;

import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;
import dev.hytalemodding.sproutwatch.ui.StatusSnapshot;
import dev.hytalemodding.sproutwatch.ui.YouTubeStatus;
import dev.hytalemodding.sproutwatch.youtube.QuotaPacer;
import dev.hytalemodding.sproutwatch.youtube.YouTubeChatSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Starts and stops the chat sources together and answers the status reads about them.
 *
 * LOCKING RULE: startListener and stopListener hold a private lock (commands may race). stopListener
 * holds it while calling each {@link ChatSource#stop()}, which joins the source thread for up to
 * {@link ChatSource#STOP_JOIN_MILLIS}. A source's state hook (statusChanged, which refreshes open
 * settings pages) and the roster suppliers run on source threads and call the status reads
 * (listenerState, listenerRunning, sourceStates, youTubeStatus, twitchClient). Therefore the status
 * reads must never take the lock: they only read the volatile {@code sources} list. The lock is
 * private so no outside code can take it by accident. Enforced by
 * ListenerControllerTest.statusReadsReturnWhileStopListenerIsBlockedInsideASourceStop and
 * ListenerControllerTest.stateHookReadingStatusFromTheSourceThreadDuringStopDoesNotDeadlock.
 */
public final class ListenerController {

    /** Builds the chat sources one Start wants, in start order (Twitch, then YouTube). */
    @FunctionalInterface
    public interface SourceFactory {
        List<ChatSource> newSources(SproutwatchConfig currentConfig);
    }

    /** What a start or stop drives besides the sources themselves. */
    public interface Hooks {
        void startTicker();
        void stopTicker();
        /** A YouTube source threw on start: it is not running, so neither is its pacer. */
        void youTubeSourceFailedToStart();
        /** The sources have joined: save the YouTube quota usage and close the client once YouTube is off. */
        void youTubeStopped();
        /** Restart safety: sweep leftover younglings out of the pen if its world is loaded. */
        void sweepPenWorld(SproutwatchConfig currentConfig);
        /** The running YouTube source's pacer, or null. Must not block (it is a status read). */
        QuotaPacer currentYouTubePacer();
        /** A source changed state (any thread): push the new status to every open settings page. */
        void statusChanged();
    }

    private final Supplier<SproutwatchConfig> config;
    private final SourceFactory sourceFactory;
    private final ChatRoster roster;
    private final DisplayNames displayNames;
    private final Hooks hooks;
    private final Logger logger;
    /** Taken only by startListener and stopListener, never by a status read (see the class comment). */
    private final Object listenerLock = new Object();
    /** The chat sources started by the last Start (empty when stopped). Replaced as a whole under the lock; read without it. */
    private volatile List<ChatSource> sources = List.of();

    public ListenerController(Supplier<SproutwatchConfig> config, SourceFactory sourceFactory, ChatRoster roster,
                              DisplayNames displayNames, Hooks hooks, Logger logger) {
        this.config = config;
        this.sourceFactory = sourceFactory;
        this.roster = roster;
        this.displayNames = displayNames;
        this.hooks = hooks;
        this.logger = logger;
    }

    /**
     * @return error text, or null on success. Stops the current run first, always (ActionsHost
     * contract): a refused restart must not leave a source listening. Locked: commands may race.
     */
    public String startListener() {
        synchronized (listenerLock) {
            stopListener();
            SproutwatchConfig currentConfig = config.get();
            String nothing = currentConfig.nothingToStartReason();
            if (nothing != null) return nothing;
            if (!currentConfig.isPenSet()) {
                return "No pen placed yet. Stand where you want it and run /sproutwatch place.";
            }
            List<ChatSource> next = sourceFactory.newSources(currentConfig);
            List<ChatSource> started = new ArrayList<>();
            for (ChatSource s : next) {
                try {
                    s.setOnStateChange(hooks::statusChanged); // Connecting... -> Stop flips on open pages at once
                    s.start();
                    started.add(s);
                } catch (RuntimeException exception) {
                    // Class name only: a message could in theory echo configuration.
                    logger.warning("Sproutwatch: a chat source failed to start: " + exception.getClass().getSimpleName());
                    if (s instanceof YouTubeChatSource) hooks.youTubeSourceFailedToStart();
                }
            }
            if (started.isEmpty()) return "No chat source could start (see the server log).";
            sources = List.copyOf(started);
            hooks.sweepPenWorld(currentConfig);
            hooks.startTicker();
            return null;
        }
    }

    /** @return true if a listener was running and has been stopped. Sprouts stay until /sproutwatch clear. */
    public boolean stopListener() {
        synchronized (listenerLock) {
            List<ChatSource> old = sources;
            sources = List.of();
            for (ChatSource s : old) s.stop();   // stop the producers (each joins <= 1 s) before clearing what they produce
            hooks.stopTicker();
            roster.clear();
            displayNames.clear();   // names belong to the session's roster
            hooks.youTubeStopped();   // saves the quota usage; closes the client once YouTube is off
            // True when a Start was in effect, even if every source has since ended on its own (chat
            // ended): the ticker was still running, so Stop did stop something.
            return !old.isEmpty();
        }
    }

    // ---- status reads: lock-free (volatile reads only) -----------------------------------------

    public String listenerState() {
        return StatusSnapshot.joinStates(sourceStates());
    }

    public boolean listenerRunning() {
        for (ChatSource s : sources) if (s.isRunning()) return true;
        return false;
    }

    public Map<String, String> sourceStates() {
        SproutwatchConfig currentConfig = config.get();
        String twitchState = null, youTubeState = null;
        for (ChatSource s : sources) {
            if (s instanceof TwitchMembershipClient) twitchState = s.getState();
            else if (s instanceof YouTubeChatSource) youTubeState = s.getState();
        }
        return StatusSnapshot.sourceStates(twitchState, currentConfig.twitchReady(), youTubeState, currentConfig.youTubeConfigured());
    }

    public YouTubeStatus youTubeStatus() {
        return YouTubeStatus.of(config.get(), hooks.currentYouTubePacer(), Clock.systemDefaultZone());
    }

    /** The started Twitch client, or null. */
    public TwitchMembershipClient twitchClient() {
        for (ChatSource s : sources) if (s instanceof TwitchMembershipClient client) return client;
        return null;
    }
}
