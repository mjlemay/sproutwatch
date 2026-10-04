package dev.hytalemodding.sproutwatch.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeRefTest {

    private static final String ID = "dQw4w9WgXcQ";
    private static final String UC = "UCabcdefghijklmnopqrstuv";

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
        "@Name", "Name", "  @Name  ",
        "https://www.youtube.com/@Name", "http://youtube.com/@Name/",
        "youtube.com/@Name/live", "https://m.youtube.com/@Name/streams",
        "https://www.youtube.com/@Name?si=abc", "https://www.youtube.com/@Name/live#x"
    })
    void handleAccepted(String in) {
        assertEquals(Optional.of("@Name"), YouTubeRef.parseHandle(in));
    }

    @Test
    void handleKeepsCharsAndCase() {
        assertEquals(Optional.of("@My_Na-me.9"), YouTubeRef.parseHandle("My_Na-me.9"));
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ", "@Na me", "Na me", "@ab", "ab", "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
        "https://example.com/@Name", "https://www.youtube.com/channel/" + UC,
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ",
        "@Na!me", "@@Name"
    })
    void handleRejected(String in) {
        assertTrue(YouTubeRef.parseHandle(in).isEmpty());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
        ID,
        "https://www.youtube.com/watch?v=" + ID,
        "https://www.youtube.com/watch?feature=share&v=" + ID + "&t=10",
        "https://youtu.be/" + ID, "youtu.be/" + ID + "?si=xyz",
        "https://www.youtube.com/live/" + ID, "https://www.youtube.com/live/" + ID + "?feature=share",
        "https://www.youtube.com/shorts/" + ID, "https://www.youtube.com/embed/" + ID,
        "https://m.youtube.com/watch?v=" + ID, "https://music.youtube.com/watch?v=" + ID,
        "  " + ID + "  "
    })
    void videoAccepted(String in) {
        assertEquals(Optional.of(ID), YouTubeRef.parseVideoId(in));
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ", "dQw4w9WgXc", "dQw4w9WgXcQQ", "dQw4w9WgX!Q",
        "https://example.com/watch?v=dQw4w9WgXcQ", "https://vimeo.com/dQw4w9WgXcQ",
        "https://www.youtube.com/@Name", "https://www.youtube.com/channel/" + UC,
        "https://www.youtube.com/watch?v=short", "https://www.youtube.com/watch"
    })
    void videoRejected(String in) {
        assertTrue(YouTubeRef.parseVideoId(in).isEmpty());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
        UC, "  " + UC + " ",
        "https://www.youtube.com/channel/" + UC,
        "https://www.youtube.com/channel/" + UC + "/live",
        "youtube.com/channel/" + UC + "/streams"
    })
    void channelAccepted(String in) {
        assertEquals(Optional.of(UC), YouTubeRef.parseChannelId(in));
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ", "UCshort", UC + "x", "XXabcdefghijklmnopqrstuv",
        "https://example.com/channel/" + UC, "https://www.youtube.com/@Name",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    })
    void channelRejected(String in) {
        assertTrue(YouTubeRef.parseChannelId(in).isEmpty());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"@名前テスト", "@नमस्ते", "@Re\u0301mi", "@abc", "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "https://youtube.com:443/@Name2"})
    void handleWideAndBoundaryAccepted(String in) {
        assertTrue(YouTubeRef.parseHandle(in).isPresent());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
        "\uFF20Name", "%40Name", "@ab", "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "YouTube.com"
    })
    void handleWideAndBoundaryRejected(String in) {
        assertTrue(YouTubeRef.parseHandle(in).isEmpty());
    }

    @Test
    void portIsStripped() {
        assertEquals(Optional.of(ID), YouTubeRef.parseVideoId("https://youtube.com:443/watch?v=" + ID));
        assertEquals(Optional.of("@Name"), YouTubeRef.parseHandle("youtube.com:443/@Name"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"https://", "@", "%%%", "youtube.com/watch?v=", "youtu.be/", "#@Name"})
    void degenerateInputsAreEmpty(String in) {
        assertTrue(YouTubeRef.parseHandle(in).isEmpty());
        assertTrue(YouTubeRef.parseVideoId(in).isEmpty());
        assertTrue(YouTubeRef.parseChannelId(in).isEmpty());
    }

    @Test
    void uppercaseSchemeAndHostAccepted() {
        assertEquals(Optional.of(ID), YouTubeRef.parseVideoId("HTTPS://WWW.YOUTUBE.COM/watch?v=" + ID));
        assertEquals(Optional.of("@Name"), YouTubeRef.parseHandle("HTTPS://WWW.YOUTUBE.COM/@Name"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
        "https://youtube.com@evil.com/@Name", "https://evil.com/?u=youtube.com/@Name",
        "https://youtube.com.evil.com/@Name", "https://notyoutube.com/@Name",
        "https://youtu.be.evil.com/dQw4w9WgXcQ", "https://youtube.com@evil.com/watch?v=dQw4w9WgXcQ",
        "https://evil.com/watch?u=youtube.com&v=dQw4w9WgXcQ"
    })
    void spoofingRejected(String in) {
        assertTrue(YouTubeRef.parseHandle(in).isEmpty());
        assertTrue(YouTubeRef.parseVideoId(in).isEmpty());
        assertTrue(YouTubeRef.parseChannelId(in).isEmpty());
    }

    @Test
    void videoParamEdgeCases() {
        assertTrue(YouTubeRef.parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQxyz").isEmpty());
        assertEquals(Optional.of("AAAAAAAAAAA"),
            YouTubeRef.parseVideoId("https://www.youtube.com/watch?v=AAAAAAAAAAA&v=BBBBBBBBBBB"));
    }

    @Test void hostMatchingIgnoresTheSystemLocale() {
        // Under a Turkish default locale "I".toLowerCase() is a dotless i; host checks must use Locale.ROOT.
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(Optional.of("dQw4w9WgXcQ"), YouTubeRef.parseVideoId("https://MUSIC.YOUTUBE.COM/watch?v=dQw4w9WgXcQ"));
            assertEquals(Optional.of("@Name"), YouTubeRef.parseHandle("HTTPS://WWW.YOUTUBE.COM/@Name"));
            assertEquals(Optional.empty(), YouTubeRef.parseHandle("YOUTUBE.COM"), "bare host still rejected");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
