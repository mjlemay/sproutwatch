package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import java.util.Locale;

/**
 * Argument handling for {@code /sproutwatch youtube …} and {@code /sproutwatch twitch …}, kept here
 * (pure, over SproutwatchActions) so the command classes only pick a usage variant by token count.
 * Every reply comes from an action, so the API key is only ever echoed masked.
 */
public final class ChatSourceCommands {

    public static final String YOUTUBE_USAGE =
        "Usage: /sproutwatch youtube on|off | handle <@handle> | key <key> | video <link|clear> | every <10-300>";
    public static final String TWITCH_USAGE = "Usage: /sproutwatch twitch on|off";
    public static final String CHECK_HINT = "Enter how often to check YouTube chat, in seconds ("
        + SproutwatchConfig.YOUTUBE_CHECK_MIN_SECONDS + " to " + SproutwatchConfig.YOUTUBE_CHECK_MAX_SECONDS
        + "). Slower checks make the daily quota last longer.";

    private ChatSourceCommands() {}

    /** Bare {@code /sproutwatch youtube}: the status lines while enabled, else how to turn it on. */
    public static String youTubeSummary(StatusSnapshot snapshot) {
        if (!snapshot.youTube().enabled()) return "YouTube chat is off. " + YOUTUBE_USAGE;
        return String.join("\n", snapshot.youTubeLine(), snapshot.youTubeChannelLine(), snapshot.youTubeKeyLine(), snapshot.youTubeQuotaLine());
    }

    /** {@code /sproutwatch youtube on|off}. */
    public static String youTube(SproutwatchActions actions, String state) {
        Boolean on = onOff(state);
        return on == null ? YOUTUBE_USAGE : actions.chatSources().setYouTubeEnabled(on);
    }

    /** {@code /sproutwatch youtube handle|key|video|every <value>}. */
    public static String youTube(SproutwatchActions actions, String setting, String value) {
        String which = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        return switch (which) {
            case "handle" -> actions.chatSources().setYouTubeHandle(value);
            case "key" -> actions.chatSources().setYouTubeKey(value);
            case "video" -> actions.chatSources().setYouTubeVideo(value != null && value.trim().equalsIgnoreCase("clear") ? "" : value);
            case "every" -> checkSeconds(actions, parseSeconds(value));
            default -> YOUTUBE_USAGE;
        };
    }

    /** Bare {@code /sproutwatch twitch}. */
    public static String twitchSummary(boolean enabled) {
        return "Twitch chat is " + (enabled ? "on" : "off") + ". " + TWITCH_USAGE;
    }

    /** {@code /sproutwatch twitch on|off}. */
    public static String twitch(SproutwatchActions actions, String state) {
        Boolean on = onOff(state);
        return on == null ? TWITCH_USAGE : actions.chatSources().setTwitchEnabled(on);
    }

    /** Check interval from the page or the command: 10 to 300 seconds, else {@link #CHECK_HINT}. */
    public static String checkSeconds(SproutwatchActions actions, Integer seconds) {
        if (seconds == null || seconds < SproutwatchConfig.YOUTUBE_CHECK_MIN_SECONDS
            || seconds > SproutwatchConfig.YOUTUBE_CHECK_MAX_SECONDS) return CHECK_HINT;
        return actions.chatSources().setYouTubeCheckSeconds(seconds);
    }

    /** Whole seconds only ("20", not "20.5" or "20s"); null for anything else. */
    static Integer parseSeconds(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.matches("[0-9]{1,4}")) return null;
        return Integer.parseInt(trimmed);
    }

    /** "on"/"off" (also true/false, yes/no), any case; null for anything else. */
    static Boolean onOff(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "on", "true", "yes" -> Boolean.TRUE;
            case "off", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }
}
