package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.chat.RosterEvent;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.youtube.LookupUnavailable;
import dev.hytalemodding.sproutwatch.youtube.NoYouTubeKey;
import dev.hytalemodding.sproutwatch.youtube.YouTubeException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The filter mode and the allow / ignore lists, each returning the reply text.
 *
 * Twitch: "alice" / "@alice". YouTube: a youtube.com link, "yt:@x", "yt:UC..." or a bare UC... ID
 * (see ViewerEntry). Channel IDs apply at once; an @handle is resolved off-thread through the
 * host, the reply says "Looking up...", and the outcome lands in lastLookupMessage() (the page,
 * via statusChanged) and in the caller's outcome callback (a command's sender).
 */
public final class ViewerListActions {

    private static final String NO_KEY_REPLY =
        "Add your YouTube API key first, or paste the channel link (youtube.com/channel/UC...).";
    static final String UNAVAILABLE_REPLY = "YouTube lookup unavailable (server stopping).";
    static final String TOO_MANY_REPLY = "Too many YouTube lookups in progress; wait a moment and try again.";
    /** Lookups allowed in flight at once; a burst of adds beyond this is refused. */
    static final int MAX_PENDING_LOOKUPS = 5;

    private final ActionsHost host;

    /** Outcome of the latest YouTube handle lookup (or its "Looking up..." while pending); null = none yet. */
    private volatile String lastLookupMessage;
    private final AtomicInteger pendingLookups = new AtomicInteger();

    ViewerListActions(ActionsHost host) {
        this.host = host;
    }

    /** Which list decides eligibility: the allow list (only listed viewers) or the ignore list (everyone else). */
    public String setFilter(boolean allowMode) {
        host.config().setAllowMode(allowMode);
        host.saveConfig();
        SproutwatchConfig config = host.config();
        if (!allowMode) {
            return "Filter is now the ignore list: everyone in chat except " + config.ignoredViewers().size() + " ignored.";
        }
        int allowedCount = config.allowedViewers().size();
        return "Filter is now the allow list: only listed viewers get a sprout ("
            + (allowedCount == 0 ? "the list is empty, so nobody until you add someone" : allowedCount + " listed") + ").";
    }

    /** What the latest YouTube @handle lookup for an allow/ignore entry came to, for the page to show. */
    public Optional<String> lastLookupMessage() {
        return Optional.ofNullable(lastLookupMessage);
    }

    public String addAllow(String raw) { return addAllow(raw, null); }
    public String removeAllow(String raw) { return removeAllow(raw, null); }
    public String addIgnore(String raw) { return addIgnore(raw, null); }
    public String removeIgnore(String raw) { return removeIgnore(raw, null); }

    /** @param onLookupOutcome also told how a YouTube handle lookup ended (on the lookup thread); may be null */
    public String addAllow(String raw, Consumer<String> onLookupOutcome) {
        return listAction(raw, true, true, onLookupOutcome);
    }

    public String removeAllow(String raw, Consumer<String> onLookupOutcome) {
        return listAction(raw, true, false, onLookupOutcome);
    }

    public String addIgnore(String raw, Consumer<String> onLookupOutcome) {
        return listAction(raw, false, true, onLookupOutcome);
    }

    public String removeIgnore(String raw, Consumer<String> onLookupOutcome) {
        return listAction(raw, false, false, onLookupOutcome);
    }

    private String listAction(String raw, boolean allowList, boolean add, Consumer<String> onOutcome) {
        ViewerEntry entry = ViewerEntry.parse(raw);
        return switch (entry) {
            case ViewerEntry.Invalid invalid -> invalid.reply();
            case ViewerEntry.Twitch twitch -> apply(twitch.login(), null, allowList, add);
            case ViewerEntry.YouTubeChannel channel -> apply(channel.key(), null, allowList, add);
            case ViewerEntry.YouTubeHandle handle -> {
                if (!add) {
                    Optional<String> known = host.config().youTubeKeyForLabel(handle.handle());
                    if (known.isPresent()) yield apply(known.get(), null, allowList, false);
                }
                yield lookUpViewer(handle.handle(), allowList, add, onOutcome);
            }
        };
    }

    /** Starts the off-thread handle lookup; the reply is immediate and the outcome is reported when it ends. */
    private String lookUpViewer(String viewerHandle, boolean allowList, boolean add, Consumer<String> onOutcome) {
        if (host.config().getYouTubeApiKey().isEmpty()) return NO_KEY_REPLY;
        if (pendingLookups.incrementAndGet() > MAX_PENDING_LOOKUPS) {
            pendingLookups.decrementAndGet();
            return TOO_MANY_REPLY;
        }
        String pending = "Looking up " + viewerHandle + " on YouTube...";
        lastLookupMessage = pending;
        CompletableFuture<String> lookup;
        try {
            lookup = host.lookUpViewerChannelId(viewerHandle);
        } catch (RuntimeException exception) {
            pendingLookups.decrementAndGet();
            throw exception;
        }
        lookup.whenComplete((channelId, error) -> {
            pendingLookups.decrementAndGet();
            String message;
            try {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    message = lookupFailure(viewerHandle, cause);
                    host.logger().info("Sproutwatch: YouTube lookup for " + viewerHandle + " failed ("
                        + (cause instanceof YouTubeException youTube ? youTube.kind() : cause.getClass().getSimpleName()) + ")");
                } else {
                    message = apply(ViewerKey.youtube(channelId), viewerHandle, allowList, add);
                }
            } catch (RuntimeException exception) {
                host.logger().info("Sproutwatch: YouTube lookup for " + viewerHandle + " failed (" + exception.getClass().getSimpleName() + ")");
                message = "YouTube lookup failed; try again.";
            }
            lastLookupMessage = message;
            try {
                host.statusChanged();
            } catch (RuntimeException exception) {
                host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", exception);
            }
            if (onOutcome != null) {
                try {
                    onOutcome.accept(message);
                } catch (RuntimeException exception) {
                    host.logger().log(Level.WARNING, "Sproutwatch lookup reply failed", exception);
                }
            }
        });
        return pending;
    }

    private static Throwable unwrap(Throwable throwable) {
        while ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    /** Reply for a failed lookup; never contains the key (YouTubeException messages are not shown at all). */
    private static String lookupFailure(String handle, Throwable cause) {
        if (cause instanceof NoYouTubeKey) return NO_KEY_REPLY;
        if (cause instanceof LookupUnavailable) return UNAVAILABLE_REPLY;
        if (!(cause instanceof YouTubeException youTube)) return "YouTube lookup failed; try again.";
        return switch (youTube.kind()) {
            case NOT_FOUND -> "No YouTube channel " + handle + " found.";
            case KEY_INVALID -> "YouTube API key rejected.";
            case QUOTA_EXCEEDED -> "YouTube quota used up for today; paste the channel link instead.";
            case TRANSIENT, REJECTED, CHAT_ENDED -> "YouTube lookup failed; try again.";
        };
    }

    /**
     * Adds or removes one stored key (Twitch login or yt:UC...) and words the reply. On add, a label
     * (the looked-up @handle) is stored with the yt: key in one config step, refreshed even when the
     * key was already listed, and shown in place of the bare ID.
     */
    private String apply(String key, String label, boolean allowList, boolean add) {
        SproutwatchConfig config = host.config();
        boolean youTube = ViewerKey.isYouTube(key);
        String list = allowList ? "allow list" : "ignore list";
        if (add) {
            boolean added = youTube && label != null
                ? config.addYouTubeEntry(allowList, key, label)
                : (allowList ? config.addAllow(key) : config.addIgnore(key));
            String shown = youTube ? config.entryDisplay(key) : key;
            if (!added) {
                if (youTube && label != null) host.saveConfig();   // the refreshed label is worth keeping
                return shown + " is already on the " + list + ".";
            }
            host.saveConfig();
            // Drop an ignored viewer from the roster (and queue) now; the roster only filters on
            // entry, and add() refuses them from here on. Their sprout ages out after GraceSeconds.
            if (!allowList) host.roster().apply(new RosterEvent.Part(key), System.currentTimeMillis());
            return "Added " + shown + " to the " + list + ".";
        }
        // Read the display before removing: the label goes with the key's last list entry.
        String shown = youTube ? (label != null && config.youTubeLabel(key).isEmpty() ? label + " (YouTube)" : config.entryDisplay(key)) : key;
        boolean removed = allowList ? config.removeAllow(key) : config.removeIgnore(key);
        if (removed) host.saveConfig();
        return removed ? "Removed " + shown + " from the " + list + "." : shown + " is not on the " + list + ".";
    }
}
