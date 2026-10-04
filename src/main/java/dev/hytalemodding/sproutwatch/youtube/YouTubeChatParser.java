package dev.hytalemodding.sproutwatch.youtube;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure parser for a YouTube Data API v3 {@code liveChat/messages} response. */
public final class YouTubeChatParser {

    public static final long DEFAULT_POLLING_INTERVAL_MILLIS = 5000;
    private static final long MIN_POLLING_INTERVAL_MILLIS = 1;
    private static final long MAX_POLLING_INTERVAL_MILLIS = 60_000;

    /** Item types that show a person speaking or paying in chat, i.e. present right now. */
    private static final Set<String> PRESENCE_TYPES = Set.of(
            "textMessageEvent",
            "superChatEvent",
            "superStickerEvent",
            "newSponsorEvent",
            "memberMilestoneChatEvent",
            "membershipGiftingEvent");

    private static final String CHAT_ENDED_EVENT = "chatEndedEvent";

    private YouTubeChatParser() {
    }

    /**
     * @throws IllegalArgumentException when {@code json} is not a JSON object. The message never
     *                                  echoes the input and no cause is chained (bodies may hold a key).
     */
    public static ChatPage parse(String json) {
        BsonDocument root = parseRoot(json);

        boolean ended = root.containsKey("offlineAt") && !root.get("offlineAt").isNull();
        List<YouTubeMessage> messages = new ArrayList<>();

        BsonValue items = root.get("items");
        if (items != null && items.isArray()) {
            for (BsonValue v : items.asArray()) {
                if (!v.isDocument()) continue;
                BsonDocument item = v.asDocument();
                String type = str(doc(item, "snippet"), "type");
                if (CHAT_ENDED_EVENT.equals(type)) {
                    ended = true;
                    continue;
                }
                if (type == null || !PRESENCE_TYPES.contains(type)) continue;
                YouTubeMessage message = toMessage(item);
                if (message != null) messages.add(message);
            }
        }

        BsonValue token = root.get("nextPageToken");
        String nextPageToken = token != null && token.isString() ? token.asString().getValue() : null;

        BsonValue interval = root.get("pollingIntervalMillis");
        long pollingIntervalMillis = sanePollingInterval(interval);

        return new ChatPage(messages, nextPageToken, pollingIntervalMillis, ended);
    }

    /** The item as a message, or null when it has no author channel id. */
    private static YouTubeMessage toMessage(BsonDocument item) {
        BsonDocument snippet = doc(item, "snippet");
        BsonDocument author = doc(item, "authorDetails");
        String channelId = str(author, "channelId");
        if (channelId == null || channelId.isEmpty()) channelId = str(snippet, "authorChannelId");
        if (channelId == null || channelId.isEmpty()) return null;

        String text = str(snippet, "displayMessage");
        if (text == null) text = str(doc(snippet, "textMessageDetails"), "messageText");
        if (text == null) text = "";

        return new YouTubeMessage(channelId, str(author, "displayName"), text, str(snippet, "publishedAt"));
    }

    /** YouTube's suggestion when it is a finite number within 1..60 000 ms, else the default. */
    private static long sanePollingInterval(BsonValue interval) {
        if (interval == null || !interval.isNumber()) return DEFAULT_POLLING_INTERVAL_MILLIS;
        double ms = interval.asNumber().doubleValue();
        if (!Double.isFinite(ms) || ms < MIN_POLLING_INTERVAL_MILLIS || ms > MAX_POLLING_INTERVAL_MILLIS) {
            return DEFAULT_POLLING_INTERVAL_MILLIS;
        }
        return (long) ms;
    }

    private static BsonDocument parseRoot(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("YouTube chat response was empty");
        }
        try {
            // BSON reads $-prefixed keys as extended JSON (e.g. {"$date": ...}); chat text is always a string
            // value, so viewers can't trigger it, only a YouTube schema change could.
            return BsonDocument.parse(json);
        } catch (RuntimeException | StackOverflowError exception) {
            // Deliberately no cause: parser messages can quote the input, which may contain an API key.
            // StackOverflowError: deeply nested input exhausts the recursive reader.
            throw new IllegalArgumentException("YouTube chat response was not a valid JSON object");
        }
    }

    /** Sub-document {@code key} of {@code d}, or an empty document when missing / not an object. */
    private static BsonDocument doc(BsonDocument d, String key) {
        BsonValue v = d.get(key);
        return v != null && v.isDocument() ? v.asDocument() : new BsonDocument();
    }

    /** String field {@code key} of {@code d}, or null when missing / not a string. */
    private static String str(BsonDocument d, String key) {
        BsonValue v = d.get(key);
        return v != null && v.isString() ? v.asString().getValue() : null;
    }
}
