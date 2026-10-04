package dev.hytalemodding.sproutwatch.youtube;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, null-safe parsers for user-supplied YouTube references. None of them throw.
 *
 * <ul>
 *   <li>Handle ({@code @Name} or a channel URL): used to auto-detect the live stream on Start.</li>
 *   <li>Video ID / link: the "paste a link" override that targets one specific stream.</li>
 *   <li>Channel ID ({@code UC...}): used for allow/ignore entries later.</li>
 * </ul>
 */
public final class YouTubeRef {

    /**
     * Handle body after the "@": 3-30 Unicode letters (with combining marks, for scripts like Devanagari), digits, underscore, hyphen or dot.
     * The length counts Java chars, not code points; the API resolves the handle anyway.
     */
    private static final String HANDLE_BODY = "[\\p{L}\\p{M}\\p{N}_.-]{3,30}";
    /** A complete handle, with or without the leading "@". */
    private static final Pattern HANDLE = Pattern.compile("@?(" + HANDLE_BODY + ")");
    /** A video ID: exactly 11 URL-safe base64 characters. */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    /** A channel ID: "UC" followed by 22 URL-safe base64 characters. */
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    /** A trailing ":port" on a URL host. */
    private static final Pattern PORT = Pattern.compile(":\\d+$");
    /** Optional scheme prefix on a URL. */
    private static final Pattern SCHEME = Pattern.compile("^(?i)https?://");
    /** Hosts accepted as YouTube. */
    private static final Set<String> YOUTUBE_HOSTS =
            Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com");
    /** The short-link host. */
    private static final String SHORT_HOST = "youtu.be";
    /** Path prefixes whose next segment is a video ID. */
    private static final Set<String> VIDEO_PATH_PREFIXES = Set.of("live", "shorts", "embed");

    private YouTubeRef() {}

    /** Returns the handle with a leading "@" (case preserved), or empty. */
    public static Optional<String> parseHandle(String input) {
        String s = clean(input);
        if (s == null) return Optional.empty();
        Url url = splitUrl(s);
        if (url != null) {
            if (!YOUTUBE_HOSTS.contains(url.host())) return Optional.empty();
            String[] seg = segments(url.path());
            if (seg.length == 0 || !seg[0].startsWith("@")) return Optional.empty();
            return matchHandle(seg[0]);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (YOUTUBE_HOSTS.contains(lower) || lower.equals(SHORT_HOST)) return Optional.empty();
        return matchHandle(s);
    }

    private static Optional<String> matchHandle(String s) {
        Matcher m = HANDLE.matcher(s);
        return m.matches() ? Optional.of("@" + m.group(1)) : Optional.empty();
    }

    /** Returns an 11-char video ID (case preserved), or empty. */
    public static Optional<String> parseVideoId(String input) {
        String s = clean(input);
        if (s == null) return Optional.empty();
        if (VIDEO_ID.matcher(s).matches()) return Optional.of(s);
        Url url = splitUrl(s);
        if (url == null) return Optional.empty();
        String host = url.host();
        String[] seg = segments(url.path());
        String candidate = null;
        if (host.equals(SHORT_HOST)) {
            if (seg.length >= 1) candidate = seg[0];
        } else if (YOUTUBE_HOSTS.contains(host)) {
            if (seg.length == 1 && seg[0].equals("watch")) {
                candidate = queryParam(url.query(), "v");
            } else if (seg.length >= 2 && VIDEO_PATH_PREFIXES.contains(seg[0])) {
                candidate = seg[1];
            }
        }
        return candidate != null && VIDEO_ID.matcher(candidate).matches()
                ? Optional.of(candidate) : Optional.empty();
    }

    /** Returns a 24-char channel ID, or empty. */
    public static Optional<String> parseChannelId(String input) {
        String s = clean(input);
        if (s == null) return Optional.empty();
        if (CHANNEL_ID.matcher(s).matches()) return Optional.of(s);
        Url url = splitUrl(s);
        if (url == null || !YOUTUBE_HOSTS.contains(url.host())) return Optional.empty();
        String[] seg = segments(url.path());
        if (seg.length >= 2 && seg[0].equals("channel") && CHANNEL_ID.matcher(seg[1]).matches()) {
            return Optional.of(seg[1]);
        }
        return Optional.empty();
    }

    /** Trims; returns null for null/blank or input containing inner whitespace. */
    private static String clean(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) return null;
        return s;
    }

    /** A split URL: lowercase host (port stripped), path, query. */
    private record Url(String host, String path, String query) {}

    /** Returns the parts if the input looks like a URL (scheme, or dotted host then "/"), else null. */
    private static Url splitUrl(String s) {
        Matcher sm = SCHEME.matcher(s);
        boolean hadScheme = sm.find();
        String rest = hadScheme ? s.substring(sm.end()) : s;
        int frag = rest.indexOf('#');
        if (frag >= 0) rest = rest.substring(0, frag);
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        if (!hadScheme && (slash < 0 || !host.contains("."))) return null;
        if (hadScheme && host.isEmpty()) return null;
        host = PORT.matcher(host.toLowerCase(Locale.ROOT)).replaceFirst("");
        return new Url(host, path, query);
    }

    private static String[] segments(String path) {
        return Arrays.stream(path.split("/")).filter(p -> !p.isEmpty()).toArray(String[]::new);
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }
}
