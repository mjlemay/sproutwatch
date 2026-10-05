package dev.hytalemodding.sproutwatch.youtube;

/** No YouTube API key is configured, so a handle cannot be looked up. */
public final class NoYouTubeKey extends RuntimeException {
    public NoYouTubeKey() {
        super("no YouTube API key", null, false, false);
    }
}
