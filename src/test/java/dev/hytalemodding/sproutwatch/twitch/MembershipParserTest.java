package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MembershipParserTest {

    @Test void parsesNamesReplyWithChannelMarker() {
        RosterEvent event = MembershipParser.parse(":justinfan1.tmi.twitch.tv 353 justinfan1 = #streamer :Alice bob  CAROL");
        assertEquals(new RosterEvent.Names(List.of("alice", "bob", "carol")), event);
    }

    @Test void parsesNamesReplyWithoutMarker() {
        RosterEvent event = MembershipParser.parse(":x.tmi.twitch.tv 353 justinfan #chan :a b c");
        assertEquals(new RosterEvent.Names(List.of("a", "b", "c")), event);
    }

    @Test void parsesJoinPartAndPrivmsgLowercased() {
        assertEquals(new RosterEvent.Join("alice"),
            MembershipParser.parse(":Alice!alice@alice.tmi.twitch.tv JOIN #streamer"));
        assertEquals(new RosterEvent.Part("bob"),
            MembershipParser.parse(":bob!bob@bob.tmi.twitch.tv PART #streamer"));
        assertEquals(new RosterEvent.Chat("carol", "hello there"),
            MembershipParser.parse(":carol!carol@carol.tmi.twitch.tv PRIVMSG #streamer :hello there"));
    }

    @Test void skipsALeadingTagBlock() {
        assertEquals(new RosterEvent.Chat("dave", "hi"),
            MembershipParser.parse("@badge-info=;color=#FF0000;display-name=Dave :dave!dave@dave.tmi.twitch.tv PRIVMSG #streamer :hi"));
    }

    @Test void returnsNullForEverythingElse() {
        assertNull(MembershipParser.parse(null));
        assertNull(MembershipParser.parse(""));
        assertNull(MembershipParser.parse("PING :tmi.twitch.tv"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv 001 justinfan1 :Welcome, GLHF!"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv 366 justinfan1 #streamer :End of /NAMES list"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv CAP * ACK :twitch.tv/membership"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv JOIN #streamer"));      // no user prefix
        assertNull(MembershipParser.parse(":x.tmi.twitch.tv 353 justinfan #chan"));  // no name list
        assertNull(MembershipParser.parse("@msg-id=sub :tmi.twitch.tv USERNOTICE #streamer"));
    }

    @Test void degenerateInputNeverThrows() {
        assertNull(MembershipParser.parse("@"));
        assertNull(MembershipParser.parse("@tags"));
        assertNull(MembershipParser.parse("@ :"));
        assertNull(MembershipParser.parse(":"));
        assertNull(MembershipParser.parse(": "));
        assertNull(MembershipParser.parse("::"));
        assertNull(MembershipParser.parse(":x 353 n #c :"));       // empty trailing name list
        assertNull(MembershipParser.parse(":!x@y JOIN #c"));       // empty nick
    }

    @Test void parsesTagBlockWithIrcv3Escapes() {
        assertEquals(new RosterEvent.Chat("dave", "hi"),
            MembershipParser.parse("@display-name=Big\\sDave;color=#FF0000 :dave!dave@dave.tmi.twitch.tv PRIVMSG #c :hi"));
    }

    @Test void chatTextKeepsInternalColonsAndSpaces() {
        assertEquals(new RosterEvent.Chat("a", "hey :) !sprout  now"),
            MembershipParser.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c :hey :) !sprout  now"));
    }

    @Test void chatWithoutTrailingTextIsEmpty() {
        assertEquals(new RosterEvent.Chat("a", ""),
            MembershipParser.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c"));
    }

    @Test void chatTextStripsCarriageReturn() {
        assertEquals(new RosterEvent.Chat("a", "!sprout"),
            MembershipParser.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c :!sprout\r"));
    }
}
