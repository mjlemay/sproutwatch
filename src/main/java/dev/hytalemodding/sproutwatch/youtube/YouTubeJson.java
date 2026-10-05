package dev.hytalemodding.sproutwatch.youtube;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lenient readers for YouTube's JSON: missing or wrong-typed fields read as empty or null and
 * never throw. Shared by the API client and the chat parser; promote to a project-wide package
 * only if a second package needs it.
 */
final class YouTubeJson {

    private YouTubeJson() {
    }

    /** Child document {@code key} of {@code parent}, or an empty document when missing or not an object. */
    static BsonDocument childDocument(BsonDocument parent, String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isDocument() ? value.asDocument() : new BsonDocument();
    }

    /** String field {@code key} of {@code document}, or null when missing or not a string. */
    static String stringField(BsonDocument document, String key) {
        BsonValue value = document.get(key);
        return value != null && value.isString() ? value.asString().getValue() : null;
    }

    /** The documents in {@code root}'s "items" array; empty when it is missing or not an array. */
    static List<BsonDocument> items(BsonDocument root) {
        List<BsonDocument> documents = new ArrayList<>();
        BsonValue items = root.get("items");
        if (items != null && items.isArray()) {
            for (BsonValue element : items.asArray()) if (element.isDocument()) documents.add(element.asDocument());
        }
        return documents;
    }

    /** {@code text} parsed as an instant, or null when it is null or not a valid timestamp. */
    static Instant parseInstant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
