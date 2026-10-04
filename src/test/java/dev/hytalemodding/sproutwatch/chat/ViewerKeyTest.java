package dev.hytalemodding.sproutwatch.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ViewerKeyTest {

    /** Twitch login alphabet: a yt: key must never match it. */
    private static final Pattern TWITCH_LOGIN = Pattern.compile("[a-z0-9_]+");

    @Test void youtubeKeyIsPrefixedAndKeepsCase() {
        assertEquals("yt:UCSJ4gkVC6NrvII8umztf0Ow", ViewerKey.youtube("UCSJ4gkVC6NrvII8umztf0Ow"));
        assertEquals("yt:UCabc", ViewerKey.youtube("  UCabc "));
        assertNotEquals(ViewerKey.youtube("UCAbc"), ViewerKey.youtube("UCabc"));
    }

    @Test void blankOrNullChannelIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ViewerKey.youtube(null));
        assertThrows(IllegalArgumentException.class, () -> ViewerKey.youtube(""));
        assertThrows(IllegalArgumentException.class, () -> ViewerKey.youtube("   "));
    }

    @Test void isYouTubeDetectsThePrefix() {
        assertTrue(ViewerKey.isYouTube("yt:UCabc"));
        assertTrue(ViewerKey.isYouTube(ViewerKey.youtube("UCSJ4gkVC6NrvII8umztf0Ow")));
        assertFalse(ViewerKey.isYouTube("ytviewer"));
        assertFalse(ViewerKey.isYouTube("some_login"));
        assertFalse(ViewerKey.isYouTube(""));
        assertFalse(ViewerKey.isYouTube(null));
    }

    @Test void youtubeKeysNeverCollideWithTwitchLogins() {
        assertFalse(TWITCH_LOGIN.matcher(":").matches(), "':' must be outside the Twitch alphabet");
        for (String id : List.of("UCSJ4gkVC6NrvII8umztf0Ow", "abc", "a", "UC_lower_123", "yt", "x:y")) {
            String key = ViewerKey.youtube(id);
            assertTrue(key.contains(":"), key);
            assertFalse(TWITCH_LOGIN.matcher(key).matches(), key);
            assertFalse(TWITCH_LOGIN.matcher(key.toLowerCase()).matches(), key);
        }
    }
}
