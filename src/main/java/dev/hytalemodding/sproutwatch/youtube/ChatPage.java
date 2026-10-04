package dev.hytalemodding.sproutwatch.youtube;

import java.util.List;

/**
 * One parsed {@code liveChat/messages} response.
 *
 * @param messages              presence-proving messages in API order; non-null (null fails fast), stored as an immutable copy
 * @param nextPageToken         token for the next read, or null when absent
 * @param pollingIntervalMillis YouTube's suggested wait before the next read
 * @param chatEnded             true when the response carries {@code offlineAt} or a {@code chatEndedEvent}
 */
public record ChatPage(List<YouTubeMessage> messages, String nextPageToken, long pollingIntervalMillis, boolean chatEnded) {
    public ChatPage {
        messages = List.copyOf(messages);
    }
}
