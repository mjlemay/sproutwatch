package dev.hytalemodding.sproutwatch.youtube;

/** The lookup thread no longer accepts or finishes work (the server is stopping). */
public final class LookupUnavailable extends RuntimeException {
    public LookupUnavailable() {
        super("YouTube lookup thread stopped", null, false, false);
    }
}
