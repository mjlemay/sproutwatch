package dev.hytalemodding.sproutwatch.youtube;

/**
 * One YouTube live chat item that proves its author is present. {@code displayName} is raw
 * (sanitized by DisplayNames); {@code text} may be empty (e.g. a new-member event).
 */
public record YouTubeMessage(String channelId, String displayName, String text, String publishedAt) {
}
