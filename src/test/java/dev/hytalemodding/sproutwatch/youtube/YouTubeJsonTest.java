package dev.hytalemodding.sproutwatch.youtube;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YouTubeJsonTest {

    @Test
    void childDocumentReturnsPresentDocument() {
        BsonDocument parent = BsonDocument.parse("{\"child\": {\"a\": \"b\"}}");
        assertEquals(BsonDocument.parse("{\"a\": \"b\"}"), YouTubeJson.childDocument(parent, "child"));
    }

    @Test
    void childDocumentIsEmptyWhenMissing() {
        assertEquals(new BsonDocument(), YouTubeJson.childDocument(new BsonDocument(), "child"));
    }

    @Test
    void childDocumentIsEmptyWhenString() {
        BsonDocument parent = BsonDocument.parse("{\"child\": \"text\"}");
        assertEquals(new BsonDocument(), YouTubeJson.childDocument(parent, "child"));
    }

    @Test
    void childDocumentIsEmptyWhenArray() {
        BsonDocument parent = BsonDocument.parse("{\"child\": [{\"a\": 1}]}");
        assertEquals(new BsonDocument(), YouTubeJson.childDocument(parent, "child"));
    }

    @Test
    void stringFieldReturnsPresentString() {
        assertEquals("value", YouTubeJson.stringField(BsonDocument.parse("{\"key\": \"value\"}"), "key"));
    }

    @Test
    void stringFieldIsNullWhenMissing() {
        assertNull(YouTubeJson.stringField(new BsonDocument(), "key"));
    }

    @Test
    void stringFieldIsNullWhenNumber() {
        assertNull(YouTubeJson.stringField(BsonDocument.parse("{\"key\": 5}"), "key"));
    }

    @Test
    void itemsKeepsOnlyDocuments() {
        BsonDocument root = BsonDocument.parse("{\"items\": [{\"id\": \"a\"}, 3, \"text\", null, [1], {\"id\": \"b\"}]}");
        assertEquals(
                List.of(BsonDocument.parse("{\"id\": \"a\"}"), BsonDocument.parse("{\"id\": \"b\"}")),
                YouTubeJson.items(root));
    }

    @Test
    void itemsIsEmptyWhenMissing() {
        assertEquals(List.of(), YouTubeJson.items(new BsonDocument()));
    }

    @Test
    void itemsIsEmptyWhenNotAnArray() {
        assertEquals(List.of(), YouTubeJson.items(BsonDocument.parse("{\"items\": {\"id\": \"a\"}}")));
        assertEquals(List.of(), YouTubeJson.items(BsonDocument.parse("{\"items\": \"text\"}")));
    }

    @Test
    void parseInstantReadsUtcTimestamp() {
        assertEquals(Instant.parse("2026-10-03T18:00:00Z"), YouTubeJson.parseInstant("2026-10-03T18:00:00Z"));
    }

    @Test
    void parseInstantReadsFractionalSecondsWithOffset() {
        assertEquals(Instant.parse("2026-10-03T18:00:00.123456Z"),
                YouTubeJson.parseInstant("2026-10-03T18:00:00.123456+00:00"));
    }

    @Test
    void parseInstantIsNullWhenInvalid() {
        assertNull(YouTubeJson.parseInstant("not a time"));
        assertNull(YouTubeJson.parseInstant(""));
    }

    @Test
    void parseInstantIsNullWhenNull() {
        assertNull(YouTubeJson.parseInstant(null));
    }
}
