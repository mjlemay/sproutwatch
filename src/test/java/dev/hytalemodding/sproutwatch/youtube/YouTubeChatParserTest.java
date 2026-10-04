package dev.hytalemodding.sproutwatch.youtube;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YouTubeChatParserTest {

    private static String fixture() throws IOException {
        try (InputStream in = YouTubeChatParserTest.class.getResourceAsStream("/youtube/chat_page.json")) {
            assertNotNull(in, "fixture missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Escapes a value for embedding inside a JSON string literal. */
    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** One item of the given type; author block omitted when channelId is null. Inputs are JSON-escaped. */
    private static String item(String type, String channelId, String name, String displayMessage) {
        String author = channelId == null ? "" : """
                , "authorDetails": {"channelId": "%s", "displayName": "%s"}"""
                .formatted(esc(channelId), esc(String.valueOf(name)));
        String message = displayMessage == null ? "" : ", \"displayMessage\": \"%s\"".formatted(esc(displayMessage));
        return """
                {"snippet": {"type": "%s", "publishedAt": "2026-10-03T12:00:00Z"%s}%s}"""
                .formatted(esc(type), message, author);
    }

    private static String page(String... items) {
        return """
                {"pollingIntervalMillis": 2000, "nextPageToken": "T", "items": [%s]}"""
                .formatted(String.join(",", items));
    }

    @Test
    void parsesRealFixture() throws IOException {
        ChatPage page = YouTubeChatParser.parse(fixture());

        assertEquals("NEXTTOKEN", page.nextPageToken());
        assertEquals(1901, page.pollingIntervalMillis());
        assertFalse(page.chatEnded());

        List<YouTubeMessage> m = page.messages();
        assertEquals(5, m.size());
        assertEquals(List.of("UCaaaaaaaaaaaaaaaaaaaaa1", "UCbbbbbbbbbbbbbbbbbbbbb2", "UCccccccccccccccccccccc3",
                        "UCaaaaaaaaaaaaaaaaaaaaa1", "UCddddddddddddddddddddd4"),
                m.stream().map(YouTubeMessage::channelId).toList());
        assertEquals(List.of("Viewer One", "Viewer Two", "Viewer Three", "Viewer One", "Viewer Four"),
                m.stream().map(YouTubeMessage::displayName).toList());
        assertEquals(List.of("hello from youtube", "!sprout", "this is so cozy", "!SPROUT ", "hi chat 👋"),
                m.stream().map(YouTubeMessage::text).toList());
        assertEquals("2026-10-03T12:00:00.000000+00:00", m.get(0).publishedAt());
        assertEquals("2026-10-03T12:00:04.000000+00:00", m.get(4).publishedAt());
    }

    @Test
    void messageListIsImmutable() throws IOException {
        ChatPage page = YouTubeChatParser.parse(fixture());
        assertThrows(UnsupportedOperationException.class, () -> page.messages().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"superChatEvent", "superStickerEvent", "newSponsorEvent",
            "memberMilestoneChatEvent", "membershipGiftingEvent"})
    void presenceEventsAreKept(String type) {
        ChatPage page = YouTubeChatParser.parse(page(item(type, "UCx", "Payer", "thanks!")));
        assertEquals(List.of(new YouTubeMessage("UCx", "Payer", "thanks!", "2026-10-03T12:00:00Z")), page.messages());
    }

    @Test
    void presenceEventWithoutTextHasEmptyText() {
        ChatPage page = YouTubeChatParser.parse(page(item("newSponsorEvent", "UCx", "New Member", null)));
        assertEquals(1, page.messages().size());
        assertEquals("", page.messages().get(0).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageDeletedEvent", "userBannedEvent", "sponsorOnlyModeStartedEvent",
            "pollEvent", "somethingBrandNewEvent", ""})
    void nonPresenceAndUnknownTypesAreSkipped(String type) {
        ChatPage page = YouTubeChatParser.parse(page(
                item("textMessageEvent", "UCa", "A", "before"),
                item(type, "UCb", "B", "x"),
                item("textMessageEvent", "UCc", "C", "after")));
        assertEquals(List.of("before", "after"), page.messages().stream().map(YouTubeMessage::text).toList());
    }

    @Test
    void itemWithoutTypeOrSnippetIsSkipped() {
        ChatPage page = YouTubeChatParser.parse("""
                {"items": [
                  {"authorDetails": {"channelId": "UCa", "displayName": "A"}},
                  {"snippet": {"displayMessage": "no type"}, "authorDetails": {"channelId": "UCb"}},
                  "not an object",
                  {"snippet": {"type": "textMessageEvent", "displayMessage": "ok"},
                   "authorDetails": {"channelId": "UCc", "displayName": "C"}}
                ]}""");
        assertEquals(List.of("ok"), page.messages().stream().map(YouTubeMessage::text).toList());
    }

    @Test
    void missingDisplayMessageFallsBackToTextMessageDetails() {
        ChatPage page = YouTubeChatParser.parse("""
                {"items": [{
                  "snippet": {"type": "textMessageEvent", "publishedAt": "P",
                              "textMessageDetails": {"messageText": "!sprout"}},
                  "authorDetails": {"channelId": "UCa", "displayName": "A"}
                }]}""");
        assertEquals("!sprout", page.messages().get(0).text());
    }

    @Test
    void noTextAnywhereGivesEmptyString() {
        ChatPage page = YouTubeChatParser.parse(page(item("textMessageEvent", "UCa", "A", null)));
        assertEquals("", page.messages().get(0).text());
    }

    @Test
    void channelIdFallsBackToSnippetAuthorChannelId() {
        ChatPage page = YouTubeChatParser.parse("""
                {"items": [{
                  "snippet": {"type": "textMessageEvent", "authorChannelId": "UCsnip", "displayMessage": "hi"}
                }]}""");
        assertEquals("UCsnip", page.messages().get(0).channelId());
    }

    @Test
    void itemWithoutChannelIdIsSkipped() {
        ChatPage page = YouTubeChatParser.parse(page(
                item("textMessageEvent", null, null, "ghost"),
                item("textMessageEvent", "", "Empty", "blank id"),
                item("textMessageEvent", "UCa", "A", "real")));
        assertEquals(List.of("real"), page.messages().stream().map(YouTubeMessage::text).toList());
    }

    @Test
    void offlineAtMeansChatEnded() {
        ChatPage page = YouTubeChatParser.parse("""
                {"offlineAt": "2026-10-03T13:00:00Z", "items": []}""");
        assertTrue(page.chatEnded());
    }

    @Test
    void chatEndedEventItemMeansChatEndedAndIsNotAMessage() {
        ChatPage page = YouTubeChatParser.parse(page(
                item("textMessageEvent", "UCa", "A", "bye"),
                item("chatEndedEvent", "UCowner", "Owner", null)));
        assertTrue(page.chatEnded());
        assertEquals(List.of("bye"), page.messages().stream().map(YouTubeMessage::text).toList());
    }

    @Test
    void missingItemsGivesEmptyListAndDefaults() {
        ChatPage page = YouTubeChatParser.parse("{\"kind\": \"youtube#liveChatMessageListResponse\"}");
        assertTrue(page.messages().isEmpty());
        assertNull(page.nextPageToken());
        assertEquals(5000, page.pollingIntervalMillis());
        assertFalse(page.chatEnded());
    }

    @Test
    void nonNumericIntervalDefaultsTo5000() {
        ChatPage page = YouTubeChatParser.parse("{\"pollingIntervalMillis\": \"soon\", \"items\": []}");
        assertEquals(5000, page.pollingIntervalMillis());
    }

    @ParameterizedTest
    @ValueSource(strings = {"30000000000", "0", "-5", "60001", "1e400", "-1e400", "NaN", "Infinity", "0.5"})
    void insaneIntervalFallsBackToDefault(String raw) {
        ChatPage page = YouTubeChatParser.parse("{\"pollingIntervalMillis\": " + raw + ", \"items\": []}");
        assertEquals(YouTubeChatParser.DEFAULT_POLLING_INTERVAL_MILLIS, page.pollingIntervalMillis());
    }

    @ParameterizedTest
    @ValueSource(strings = {"60000:60000", "1901.0:1901", "1:1", "1901:1901"})
    void saneIntervalIsKept(String c) {
        String[] parts = c.split(":");
        ChatPage page = YouTubeChatParser.parse("{\"pollingIntervalMillis\": " + parts[0] + ", \"items\": []}");
        assertEquals(Long.parseLong(parts[1]), page.pollingIntervalMillis());
    }

    @Test
    void emptyOfflineAtStillMeansEnded() {
        assertTrue(YouTubeChatParser.parse("{\"offlineAt\": \"\", \"items\": []}").chatEnded());
    }

    @Test
    void unicodeEscapesAndSurrogatePairsDecode() {
        ChatPage page = YouTubeChatParser.parse("""
                {"items": [{
                  "snippet": {"type": "textMessageEvent", "displayMessage": "caf\\u00e9 \\ud83d\\udc4b"},
                  "authorDetails": {"channelId": "UCa", "displayName": "Ren\\u00e9e"}
                }]}""");
        assertEquals("caf\u00e9 \uD83D\uDC4B", page.messages().get(0).text());
        assertEquals("Ren\u00e9e", page.messages().get(0).displayName());
    }

    @Test
    void nonStringDisplayMessageFallsBackToTextMessageDetails() {
        ChatPage page = YouTubeChatParser.parse("""
                {"items": [{
                  "snippet": {"type": "textMessageEvent", "displayMessage": {"odd": true},
                              "textMessageDetails": {"messageText": "!sprout"}},
                  "authorDetails": {"channelId": "UCa", "displayName": "A"}
                }]}""");
        assertEquals("!sprout", page.messages().get(0).text());
    }

    @Test
    void itemHelperEscapesQuotesAndBackslashes() {
        ChatPage page = YouTubeChatParser.parse(page(item("textMessageEvent", "UCa", "A \"B\"", "say \"hi\" \\o/")));
        assertEquals("say \"hi\" \\o/", page.messages().get(0).text());
        assertEquals("A \"B\"", page.messages().get(0).displayName());
    }

    @Test
    void deeplyNestedInputThrowsConstantMessageWithoutCause() {
        int depth = 50_000;
        String json = "{\"items\": " + "[".repeat(depth) + "]".repeat(depth) + "}";
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> YouTubeChatParser.parse(json));
        assertEquals("YouTube chat response was not a valid JSON object", exception.getMessage());
        assertNull(exception.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json key=AIzaSECRETSECRET", "{\"items\": [", "[1,2,3]", "null"})
    void malformedJsonThrowsWithoutEchoingInput(String json) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> YouTubeChatParser.parse(json));
        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().contains("AIza"), exception.getMessage());
        if (!json.isBlank()) assertFalse(exception.getMessage().contains(json), exception.getMessage());
        // the parser's own exception text may quote the input, so it must not be chained either
        assertNull(exception.getCause());
    }

    @Test
    void nullInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> YouTubeChatParser.parse(null));
    }
}
