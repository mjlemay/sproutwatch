package dev.hytalemodding.sproutwatch.youtube;

/**
 * A failed YouTube Data API call, classified for the caller's state machine.
 *
 * Messages are built only from the call name, HTTP status and API reason code; they never
 * contain a URL, response body or API key, and no cause is chained (a cause's message could).
 */
public final class YouTubeException extends Exception {

    public enum Kind {
        /** Key missing/invalid, API not enabled for the key's project, or key restrictions block us. Stop. */
        KEY_INVALID,
        /** Daily quota or rate limit hit. Stop polling until reset. */
        QUOTA_EXCEEDED,
        /** The live chat ended, is disabled, or no longer exists. Stop. */
        CHAT_ENDED,
        /** Handle, video or resource does not exist (HTTP 404 or an empty result). */
        NOT_FOUND,
        /** Network failure, timeout, 5xx or an unreadable 2xx body. Retry with backoff. */
        TRANSIENT,
        /**
         * Any other 4xx (e.g. invalidPageToken, bad parameter). Retrying the same request would fail
         * the same way, so callers should not loop on it.
         */
        REJECTED
    }

    private final Kind kind;

    public YouTubeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
