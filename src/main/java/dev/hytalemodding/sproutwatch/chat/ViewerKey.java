package dev.hytalemodding.sproutwatch.chat;

/**
 * Roster keys across chat platforms. Twitch keys stay bare lowercase logins (no migration: the
 * allow/ignore lists and the queue keep working unchanged); YouTube authors are keyed
 * {@code yt:<channelId>}. ':' is not legal in a Twitch login ([a-z0-9_]), so the two namespaces
 * never collide.
 */
public final class ViewerKey {

    public static final String YOUTUBE_PREFIX = "yt:";

    private ViewerKey() {}

    /** Channel IDs are case-sensitive ("UC..."), so the ID is trimmed but never lowercased. */
    public static String youtube(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("YouTube channel ID is blank");
        }
        return YOUTUBE_PREFIX + channelId.trim();
    }

    public static boolean isYouTube(String key) {
        return key != null && key.startsWith(YOUTUBE_PREFIX);
    }
}
