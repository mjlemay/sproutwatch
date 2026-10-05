package dev.hytalemodding.sproutwatch.ui;

import java.util.Locale;

/**
 * Argument handling for {@code /sproutwatch youtube …} and {@code /sproutwatch twitch …}, kept here
 * (pure, over SproutwatchActions) so the command classes only pick a usage variant by token count.
 * Every reply comes from an action, so the API key is only ever echoed masked.
 */
public final class ChatSourceCommands {

    public static final String YOUTUBE_USAGE =
        "Usage: /sproutwatch youtube on|off | handle <@handle> | key <key> | video <link|clear> | hours <1-24>";
    public static final String TWITCH_USAGE = "Usage: /sproutwatch twitch on|off";
    public static final String HOURS_HINT =
        "Enter your stream length in hours (1 to 24). Set this to your longest stream.";

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

    /** {@code /sproutwatch youtube handle|key|video|hours <value>}. */
    public static String youTube(SproutwatchActions actions, String setting, String value) {
        String which = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        return switch (which) {
            case "handle" -> actions.chatSources().setYouTubeHandle(value);
            case "key" -> actions.chatSources().setYouTubeKey(value);
            case "video" -> actions.chatSources().setYouTubeVideo(value != null && value.trim().equalsIgnoreCase("clear") ? "" : value);
            case "hours" -> streamHours(actions, parseHours(value));
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

    /** Stream length from the page or the command: 1 to 24 hours, else {@link #HOURS_HINT}. */
    public static String streamHours(SproutwatchActions actions, Double hours) {
        if (hours == null || !Double.isFinite(hours) || hours < 1 || hours > 24) return HOURS_HINT;
        return actions.chatSources().setYouTubeStreamHours(hours);
    }

    /** Whole hours 1 to 24 only ("6", not "2.5"); null for anything else. */
    static Double parseHours(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.matches("[0-9]{1,2}")) return null;
        int wholeHours = Integer.parseInt(trimmed);
        return wholeHours >= 1 && wholeHours <= 24 ? (double) wholeHours : null;
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
