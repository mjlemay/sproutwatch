package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.youtube.YouTubeRef;

import java.util.Locale;
import java.util.Optional;

/**
 * What an allow/ignore input names. Bare {@code name} / {@code @name} stay Twitch (Twitch users type
 * "@name"); YouTube needs a youtube.com link, a {@code yt:} prefix, or a bare {@code UC...} channel ID.
 */
sealed interface ViewerEntry {

    String INVALID_YOUTUBE =
        "Invalid YouTube channel. Use youtube.com/@name, yt:@name, or the channel link (youtube.com/channel/UC...).";

    /** A Twitch login, normalized. */
    record Twitch(String login) implements ViewerEntry {}

    /** A resolved YouTube roster key, {@code yt:UC...}. */
    record YouTubeChannel(String key) implements ViewerEntry {}

    /** A YouTube {@code @handle} that needs a lookup to become a channel ID. */
    record YouTubeHandle(String handle) implements ViewerEntry {}

    /** Unusable input; {@code reply} is the message to show. */
    record Invalid(String reply) implements ViewerEntry {}

    static ViewerEntry parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        boolean prefixed = s.regionMatches(true, 0, ViewerKey.YOUTUBE_PREFIX, 0, ViewerKey.YOUTUBE_PREFIX.length());
        if (prefixed) return youTube(s.substring(ViewerKey.YOUTUBE_PREFIX.length()));
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return youTube(s);
        Optional<String> id = YouTubeRef.parseChannelId(s);   // a bare, case-exact UC... ID
        if (id.isPresent()) return new YouTubeChannel(ViewerKey.youtube(id.get()));
        String login = SproutwatchConfig.normalizeChannel(s);
        return login.isEmpty() ? new Invalid("Invalid user name.") : new Twitch(login);
    }

    /** The part after "yt:" or a youtube link: a channel ID/URL, or a handle/handle URL. */
    private static ViewerEntry youTube(String s) {
        Optional<String> id = YouTubeRef.parseChannelId(s);
        if (id.isPresent()) return new YouTubeChannel(ViewerKey.youtube(id.get()));
        Optional<String> handle = YouTubeRef.parseHandle(s);
        if (handle.isPresent()) return new YouTubeHandle(handle.get());
        return new Invalid(INVALID_YOUTUBE);
    }
}
