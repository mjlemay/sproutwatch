package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.youtube.YouTubeRef;

import java.util.Map;
import java.util.Optional;

/**
 * Chat source setup and listener control, each returning the reply text: the Twitch channel, the
 * YouTube settings, start, stop, Begin and auto-start on boot. A setting change that matters to a
 * running listener restarts it.
 */
public final class ChatSourceActions {

    private final ActionsHost host;

    ChatSourceActions(ActionsHost host) {
        this.host = host;
    }

    public String setChannel(String raw) {
        String channel = SproutwatchConfig.normalizeChannel(raw);
        if (channel.isEmpty()) return "Invalid channel name.";
        host.config().setTwitchChannel(channel);
        host.saveConfig();
        if (!host.config().isTwitchEnabled()) return "Channel set to #" + channel + " (Twitch chat is off).";
        if (host.listenerRunning()) {
            String error = host.startListener();
            return error != null ? error : "Channel set to #" + channel + "; listener restarted.";
        }
        return "Channel set to #" + channel + ". Run /sproutwatch start to begin.";
    }

    /** The page's Remove: clears the Twitch channel; a running listener restarts without Twitch. */
    public String removeChannel() {
        boolean before = host.config().twitchReady();
        host.config().setTwitchChannel("");
        host.saveConfig();
        return restartIfRunning("Twitch channel removed", before);
    }

    /** Describes what actually started (read back from the host), plus what could not. */
    public String startListener() {
        String error = host.startListener();
        if (error != null) return error;
        SproutwatchConfig config = host.config();
        Map<String, String> states = host.sourceStates();
        boolean twitch = started(states, StatusSnapshot.TWITCH);
        boolean youTube = started(states, StatusSnapshot.YOUTUBE);
        String every = "; one sprout every " + config.getTickSeconds() + "s.";
        String message;
        if (twitch && youTube) {
            message = "Sproutwatch watching Twitch #" + config.getTwitchChannel() + " and YouTube " + YouTubeStatus.target(config) + every;
        } else if (twitch) {
            message = "Sproutwatch watching #" + config.getTwitchChannel() + every;
        } else if (youTube) {
            message = "Sproutwatch watching YouTube " + YouTubeStatus.target(config) + every;
        } else {
            return "Sproutwatch started, but no chat source started (see the server log).";
        }
        if (config.twitchReady() && !twitch) message += " Twitch could not start (see the server log).";
        if (config.youTubeConfigured() && !youTube) message += " YouTube could not start (see the server log).";
        String missing = YouTubeStatus.missing(config);
        if (config.isYouTubeEnabled() && missing != null) message += " YouTube is on but needs " + missing + ".";
        return message;
    }

    /** A source the last start actually started: listed with a state other than "stopped". */
    private static boolean started(Map<String, String> states, String name) {
        String state = states.get(name);
        return state != null && !state.equals("stopped");
    }

    public String setTwitchEnabled(boolean on) {
        boolean before = host.config().twitchReady();
        host.config().setTwitchEnabled(on);
        host.saveConfig();
        return restartIfRunning("Twitch chat is " + (on ? "on" : "off"), before || host.config().twitchReady());
    }

    public String setYouTubeEnabled(boolean on) {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeEnabled(on);
        host.saveConfig();
        String reply = restartIfRunning("YouTube chat is " + (on ? "on" : "off"), youTubeAffected(before));
        String missing = YouTubeStatus.missing(host.config());
        return on && missing != null ? reply + " It still needs " + missing + "." : reply;
    }

    public String setYouTubeHandle(String raw) {
        Optional<String> handle = YouTubeRef.parseHandle(raw);
        if (handle.isEmpty()) return "Invalid YouTube handle. Use @name or a youtube.com/@name link.";
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeHandle(handle.get());
        host.saveConfig();
        return restartIfRunning("YouTube channel set to " + handle.get(), youTubeAffected(before));
    }

    /** Saves the API key. Replies never contain the key, only {@link StatusSnapshot#maskedKey}. */
    public String setYouTubeKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) return "Paste your YouTube API key (from Google Cloud Console).";
        if (key.chars().anyMatch(Character::isWhitespace)) return "That is not an API key: it contains spaces.";
        if (key.length() < 20) return "That is not an API key: it is too short.";
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeApiKey(key);
        host.saveConfig();
        return restartIfRunning("YouTube API key saved (" + StatusSnapshot.maskedKey(key) + ")", youTubeAffected(before));
    }

    /** The page's Remove: clears the @handle (a pasted stream link, if any, still works). */
    public String removeYouTubeHandle() {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeHandle("");
        host.saveConfig();
        return restartIfRunning("YouTube channel removed", youTubeAffected(before));
    }

    /** The page's Remove: deletes the saved API key; YouTube cannot start until a new one is saved. */
    public String removeYouTubeKey() {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeApiKey("");
        host.saveConfig();
        return restartIfRunning("YouTube API key removed", youTubeAffected(before));
    }

    /** A pasted watch link or video ID overrides finding the stream from the handle; blank clears it. */
    public String setYouTubeVideo(String raw) {
        boolean before = host.config().youTubeConfigured();
        String base;
        if (raw == null || raw.isBlank()) {
            host.config().setYouTubeVideo("");
            base = "YouTube stream link cleared; the live stream is found from your @handle";
        } else {
            Optional<String> id = YouTubeRef.parseVideoId(raw);
            if (id.isEmpty()) return "Invalid YouTube stream link. Paste a watch link or the 11-character video ID.";
            host.config().setYouTubeVideo(id.get());
            base = "YouTube stream set to video " + id.get();
        }
        host.saveConfig();
        return restartIfRunning(base, youTubeAffected(before));
    }

    public String setYouTubeStreamHours(double hours) {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeStreamHours(hours);
        host.saveConfig();
        double streamHours = host.config().getYouTubeStreamHours();
        String shown = streamHours == Math.rint(streamHours) ? Long.toString((long) streamHours) : Double.toString(streamHours);
        return restartIfRunning("YouTube stream length is now " + shown
            + "h (chat reads are paced so the daily quota lasts that long)", youTubeAffected(before));
    }

    /**
     * A YouTube change matters to a running listener only when YouTube was startable before it or is
     * after it; otherwise restarting would only drop the roster for nothing.
     */
    private boolean youTubeAffected(boolean configuredBefore) {
        return configuredBefore || host.config().youTubeConfigured();
    }

    /**
     * base + "." when stopped or unaffected; else restarts: base + "; listener restarted.". A refused
     * restart has stopped the listener (ActionsHost contract), and the reply says so.
     */
    private String restartIfRunning(String base, boolean affected) {
        if (!affected || !host.listenerRunning()) return base + ".";
        String error = host.startListener();
        if (error == null) return base + "; listener restarted.";
        if (host.config().nothingToStartReason() != null) return base + ". Nothing else is set up, so the listener stopped.";
        return base + ". " + error + " The listener stopped.";
    }

    /** The Pen tab's Begin (Wrangle Viewers): start unless already running. @return the error to show, or empty when the page may close. */
    public Optional<String> begin() {
        if (host.listenerRunning()) return Optional.empty();
        return Optional.ofNullable(host.startListener());
    }

    public String stopListener() {
        return host.stopListener() ? "Sproutwatch stopped." : "Sproutwatch was not running.";
    }

    /** Whether the listener starts by itself when the world loads; it sits with start and stop. */
    public String setAutoStart(boolean on) {
        host.config().setAutoStartOnBoot(on);
        host.saveConfig();
        return on
            ? "Auto-start on boot is now on: the listener reconnects when the world loads."
            : "Auto-start on boot is now off: press Start listener after each launch.";
    }
}
