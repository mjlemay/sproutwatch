package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import dev.hytalemodding.sproutwatch.config.CreaturePreset;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenReconciler;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.prefab.PenPlacer;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.youtube.YouTubeRef;
import dev.hytalemodding.sproutwatch.youtube.YtException;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every mutation the settings page or a chat command can perform, each returning the reply text.
 * Shared by SproutwatchCommand and SproutwatchSettingsPage so both behave identically. Work that
 * must run on a world thread (place, clear) is queued through the host and answered with an
 * interim message; the final result reaches the player as a chat message from the world thread.
 */
public final class SproutwatchActions {

    private final ActionsHost host;

    public SproutwatchActions(ActionsHost host) {
        this.host = host;
    }

    public SproutwatchConfig config() {
        return host.config();
    }

    public String setChannel(String raw) {
        String channel = SproutwatchConfig.normalizeChannel(raw);
        if (channel.isEmpty()) return "Invalid channel name.";
        host.config().setTwitchChannel(channel);
        host.saveConfig();
        if (!host.config().isTwitchEnabled()) return "Channel set to #" + channel + " (Twitch chat is off).";
        if (host.listenerRunning()) {
            String err = host.startListener();
            return err != null ? err : "Channel set to #" + channel + "; listener restarted.";
        }
        return "Channel set to #" + channel + ". Run /sproutwatch start to begin.";
    }

    /** The page's Remove: clears the Twitch channel; a running listener restarts without Twitch. */
    public String removeChannel() {
        boolean before = host.config().twitchReady();
        host.config().setTwitchChannel("");
        host.saveConfig();
        return restartIfRunning("Twitch channel removed", before);
    }

    /** Describes what actually started (read back from the host), plus what could not. */
    public String startListener() {
        String err = host.startListener();
        if (err != null) return err;
        SproutwatchConfig c = host.config();
        Map<String, String> states = host.sourceStates();
        boolean twitch = started(states, StatusSnapshot.TWITCH);
        boolean youTube = started(states, StatusSnapshot.YOUTUBE);
        String every = "; one sprout every " + c.getTickSeconds() + "s.";
        String msg;
        if (twitch && youTube) {
            msg = "Sproutwatch watching Twitch #" + c.getTwitchChannel() + " and YouTube " + YouTubeStatus.target(c) + every;
        } else if (twitch) {
            msg = "Sproutwatch watching #" + c.getTwitchChannel() + every;
        } else if (youTube) {
            msg = "Sproutwatch watching YouTube " + YouTubeStatus.target(c) + every;
        } else {
            return "Sproutwatch started, but no chat source started (see the server log).";
        }
        if (c.twitchReady() && !twitch) msg += " Twitch could not start (see the server log).";
        if (c.youTubeConfigured() && !youTube) msg += " YouTube could not start (see the server log).";
        String missing = YouTubeStatus.missing(c);
        if (c.isYouTubeEnabled() && missing != null) msg += " YouTube is on but needs " + missing + ".";
        return msg;
    }

    /** A source the last start actually started: listed with a state other than "stopped". */
    private static boolean started(Map<String, String> states, String name) {
        String state = states.get(name);
        return state != null && !state.equals("stopped");
    }

    // ---- chat sources ------------------------------------------------------------------------

    public String setTwitchEnabled(boolean on) {
        boolean before = host.config().twitchReady();
        host.config().setTwitchEnabled(on);
        host.saveConfig();
        return restartIfRunning("Twitch chat is " + (on ? "on" : "off"), before || host.config().twitchReady());
    }

    public String setYouTubeEnabled(boolean on) {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeEnabled(on);
        host.saveConfig();
        String reply = restartIfRunning("YouTube chat is " + (on ? "on" : "off"), youTubeAffected(before));
        String missing = YouTubeStatus.missing(host.config());
        return on && missing != null ? reply + " It still needs " + missing + "." : reply;
    }

    public String setYouTubeHandle(String raw) {
        Optional<String> handle = YouTubeRef.parseHandle(raw);
        if (handle.isEmpty()) return "Invalid YouTube handle. Use @name or a youtube.com/@name link.";
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeHandle(handle.get());
        host.saveConfig();
        return restartIfRunning("YouTube channel set to " + handle.get(), youTubeAffected(before));
    }

    /** Saves the API key. Replies never contain the key, only {@link StatusSnapshot#maskedKey}. */
    public String setYouTubeKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) return "Paste your YouTube API key (from Google Cloud Console).";
        if (key.chars().anyMatch(Character::isWhitespace)) return "That is not an API key: it contains spaces.";
        if (key.length() < 20) return "That is not an API key: it is too short.";
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeApiKey(key);
        host.saveConfig();
        return restartIfRunning("YouTube API key saved (" + StatusSnapshot.maskedKey(key) + ")", youTubeAffected(before));
    }

    /** The page's Remove: clears the @handle (a pasted stream link, if any, still works). */
    public String removeYouTubeHandle() {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeHandle("");
        host.saveConfig();
        return restartIfRunning("YouTube channel removed", youTubeAffected(before));
    }

    /** The page's Remove: deletes the saved API key; YouTube cannot start until a new one is saved. */
    public String removeYouTubeKey() {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeApiKey("");
        host.saveConfig();
        return restartIfRunning("YouTube API key removed", youTubeAffected(before));
    }

    /** A pasted watch link or video ID overrides finding the stream from the handle; blank clears it. */
    public String setYouTubeVideo(String raw) {
        boolean before = host.config().youTubeConfigured();
        String base;
        if (raw == null || raw.isBlank()) {
            host.config().setYouTubeVideo("");
            base = "YouTube stream link cleared; the live stream is found from your @handle";
        } else {
            Optional<String> id = YouTubeRef.parseVideoId(raw);
            if (id.isEmpty()) return "Invalid YouTube stream link. Paste a watch link or the 11-character video ID.";
            host.config().setYouTubeVideo(id.get());
            base = "YouTube stream set to video " + id.get();
        }
        host.saveConfig();
        return restartIfRunning(base, youTubeAffected(before));
    }

    public String setYouTubeStreamHours(double hours) {
        boolean before = host.config().youTubeConfigured();
        host.config().setYouTubeStreamHours(hours);
        host.saveConfig();
        double h = host.config().getYouTubeStreamHours();
        String shown = h == Math.rint(h) ? Long.toString((long) h) : Double.toString(h);
        return restartIfRunning("YouTube stream length is now " + shown
            + "h (chat reads are paced so the daily quota lasts that long)", youTubeAffected(before));
    }

    /**
     * A YouTube change matters to a running listener only when YouTube was startable before it or is
     * after it; otherwise restarting would only drop the roster for nothing.
     */
    private boolean youTubeAffected(boolean configuredBefore) {
        return configuredBefore || host.config().youTubeConfigured();
    }

    /**
     * base + "." when stopped or unaffected; else restarts: base + "; listener restarted.". A refused
     * restart has stopped the listener (ActionsHost contract), and the reply says so.
     */
    private String restartIfRunning(String base, boolean affected) {
        if (!affected || !host.listenerRunning()) return base + ".";
        String err = host.startListener();
        if (err == null) return base + "; listener restarted.";
        if (host.config().nothingToStartReason() != null) return base + ". Nothing else is set up, so the listener stopped.";
        return base + ". " + err + " The listener stopped.";
    }

    /** The Pen tab's Begin (Wrangle Viewers): start unless already running. @return the error to show, or empty when the page may close. */
    public Optional<String> begin() {
        if (host.listenerRunning()) return Optional.empty();
        return Optional.ofNullable(host.startListener());
    }

    public String stopListener() {
        return host.stopListener() ? "Sproutwatch stopped." : "Sproutwatch was not running.";
    }

    /**
     * Queues the pen placement on the sender's world thread and answers at once; the outcome reaches
     * the sender as a chat message from {@link #placeOnWorldThread}. Runs on the caller's thread.
     * @param sender the player to centre the pen on; must be non-null in production (the fake host ignores it)
     */
    public String place(PlayerRef sender) {
        ActionsHost.WorldQueue q = host.runOnPlayerWorld(sender, world -> placeOnWorldThread(sender, world));
        return switch (q) {
            case QUEUED -> "Placing the pen...";
            case NOT_LOADED -> "Your world is not loaded.";
            case REJECTED -> "Your world is unloading; try again.";
        };
    }

    /** Sweeps the old pen, pastes the prefab centred on the sender and saves the config. Runs on the world thread. */
    private void placeOnWorldThread(PlayerRef sender, World world) {
        try {
            Ref<EntityStore> ref = sender.getReference();
            if (ref == null || !ref.isValid()) return;
            Store<EntityStore> store = ref.getStore();
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) return;
            Vector3d p = t.getPosition();
            Vector3i feet = new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            String swept = sweepOldPen(world);
            String msg = PenPlacer.place(world, store, feet, world.getWorldConfig().getUuid(), host.config(), host.logger());
            host.saveConfig();
            sender.sendMessage(Message.raw(msg + swept));
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Sproutwatch place failed", e);
            sender.sendMessage(Message.raw("Pen placement failed: " + e.getMessage() + " (see server log)"));
        }
        // No tick may be running; push the new pen to open settings pages now. Kept outside the
        // placement try so a refresh failure can never be reported as a failed placement.
        try {
            host.statusChanged();
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", e);
        }
    }

    /**
     * Re-placing the pen: remove the previous pen's sprouts first (registry + any youngling of a
     * configured role inside the old bounds) so they do not linger untracked. Runs on the new
     * pen's world thread; an old pen in another loaded world is swept on that world's thread.
     * @return a suffix for the placement reply
     */
    private String sweepOldPen(World newWorld) {
        SproutwatchConfig c = host.config();
        if (!c.isPenSet()) return "";
        PenBounds old = PenBounds.fromConfig(c);
        World oldWorld = host.penWorld();
        if (oldWorld == newWorld) {
            int n = PenClearer.clear(newWorld, host.registry(), host.roleSet(), old, host.logger());
            return " Cleared " + n + " sprout(s) from the old pen.";
        }
        if (oldWorld != null) {
            PenRegistry registry = host.registry();
            Set<String> roles = host.roleSet();
            Logger log = host.logger();
            host.runOnWorld(oldWorld, () -> PenClearer.clear(oldWorld, registry, roles, old, log));
            return " Clearing the old pen in its own world.";
        }
        int n = host.registry().clear().size();
        return " Old pen world not loaded; forgot " + n + " tracked sprout(s).";
    }

    /** Drops a /sproutwatch test viewer and its sprout at once. */
    public String removeGuest(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (!host.roster().removeGuest(login)) return login + " is not a test viewer (/sproutwatch test list).";
        ActionsHost.WorldQueue q = host.runOnPenWorld(w -> {
            try {
                PenClearer.despawn(w, host.registry(), List.of(login), host.logger());
                host.statusChanged();
            } catch (Exception e) {
                host.logger().log(Level.WARNING, "Sproutwatch guest despawn failed", e);
            }
        });
        return switch (q) {
            case QUEUED -> login + " removed from the test viewers; despawning their sprout...";
            case NOT_LOADED -> {
                host.registry().remove(login);
                yield login + " removed from the test viewers (pen world not loaded).";
            }
            case REJECTED -> login + " removed from the test viewers; the sprout goes on the next tick.";
        };
    }

    public String clear() {
        host.roster().forgetGuests(); // test viewers would otherwise respawn first (firstSeen 0)
        ActionsHost.WorldQueue q = host.runOnPenWorld(w -> {
            try {
                PenClearer.clear(w, host.registry(), host.roleSet(), PenBounds.fromConfig(host.config()), host.logger());
                host.statusChanged(); // no tick may be running; push the emptied pen to open settings pages now
            } catch (Exception e) {
                host.logger().log(Level.WARNING, "Sproutwatch clear failed", e);
            }
        });
        return switch (q) {
            case QUEUED -> "Clearing the pen...";
            case NOT_LOADED -> "Pen world not loaded; forgot " + host.registry().clear().size() + " tracked sprout(s).";
            case REJECTED -> "Pen world is unloading; try again.";
        };
    }

    public String setPersist(boolean on) {
        host.config().setPersistSprouts(on);
        host.saveConfig();
        return on
            ? "Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced."
            : "Persist is now off: sprouts despawn " + host.config().getGraceSeconds()
                + "s after their viewer leaves (any already past that window go on the next tick).";
    }

    public String setAutoStart(boolean on) {
        host.config().setAutoStartOnBoot(on);
        host.saveConfig();
        return on
            ? "Auto-start on boot is now on: the listener reconnects when the world loads."
            : "Auto-start on boot is now off: press Start listener after each launch.";
    }

    /** Which list decides eligibility: the allow list (only listed viewers) or the ignore list (everyone else). */
    public String setFilter(boolean allowMode) {
        host.config().setAllowMode(allowMode);
        host.saveConfig();
        SproutwatchConfig c = host.config();
        if (!allowMode) {
            return "Filter is now the ignore list: everyone in chat except " + c.ignoredLogins().size() + " ignored.";
        }
        int n = c.allowedLogins().size();
        return "Filter is now the allow list: only listed viewers get a sprout ("
            + (n == 0 ? "the list is empty, so nobody until you add someone" : n + " listed") + ").";
    }

    /** Pen tab Creatures dropdown: save, and clear the pen so it refills with the new kind (guests kept). */
    public String setCreatures(String id) {
        CreaturePreset next = CreaturePreset.parse(id);
        if (next.id().equals(host.config().getCreatures())) return "Creatures are already " + next.displayName() + ".";
        host.config().setCreatures(next.id());
        host.saveConfig();
        ActionsHost.WorldQueue q = host.runOnPenWorld(w -> {
            try {
                PenClearer.clear(w, host.registry(), host.roleSet(), PenBounds.fromConfig(host.config()), host.logger());
                host.statusChanged();
            } catch (Exception e) {
                host.logger().log(Level.WARNING, "Sproutwatch creature switch clear failed", e);
            }
        });
        String base = "Creatures: " + next.displayName() + ".";
        return switch (q) {
            case QUEUED -> base + " Clearing the pen; it refills one per tick.";
            case NOT_LOADED -> base + " Pen world not loaded; forgot " + host.registry().clear().size() + " tracked sprout(s).";
            case REJECTED -> base + " Pen world is unloading; clear the pen when it is back.";
        };
    }

    /**
     * Saves the cap and, when the pen already holds more, trims the surplus at once (absent viewers'
     * sprouts first, then the newest arrivals) so the change is visible without a Clear.
     */
    public String setMaxSprouts(int max) {
        host.config().setMaxSprouts(max);
        host.saveConfig();
        int cap = host.config().getMaxSprouts();
        String base = "Max sprouts is now " + cap;
        List<String> extra = PenReconciler.trim(host.registry().lastSeenMap(), host.roster().snapshot(), cap, host.roster().guests());
        if (extra.isEmpty()) return base + ".";
        ActionsHost.WorldQueue q = host.runOnPenWorld(w -> {
            try {
                PenClearer.despawn(w, host.registry(), extra, host.logger());
                host.statusChanged();
            } catch (Exception e) {
                host.logger().log(Level.WARNING, "Sproutwatch trim failed", e);
            }
        });
        return switch (q) {
            case QUEUED -> base + "; removing " + extra.size() + " extra sprout(s)...";
            case NOT_LOADED -> {
                for (String login : extra) host.registry().remove(login);
                yield base + "; pen world not loaded, forgot " + extra.size() + " tracked sprout(s).";
            }
            case REJECTED -> base + "; pen world is unloading, the extra sprouts go on the next tick.";
        };
    }

    public String setTickSeconds(int seconds) {
        host.config().setTickSeconds(seconds);
        host.saveConfig();
        if (host.tickerRunning()) host.restartTicker();
        return "Tick interval is now " + host.config().getTickSeconds() + "s.";
    }

    // ---- allow / ignore lists ------------------------------------------------------------------
    // Twitch: "alice" / "@alice". YouTube: a youtube.com link, "yt:@x", "yt:UC..." or a bare UC... ID
    // (see ViewerEntry). Channel IDs apply at once; an @handle is resolved off-thread through the
    // host, the reply says "Looking up...", and the outcome lands in lastLookupMessage() (the page,
    // via statusChanged) and in the caller's outcome callback (a command's sender).

    private static final String NO_KEY_REPLY =
        "Add your YouTube API key first, or paste the channel link (youtube.com/channel/UC...).";
    static final String UNAVAILABLE_REPLY = "YouTube lookup unavailable (server stopping).";
    static final String TOO_MANY_REPLY = "Too many YouTube lookups in progress; wait a moment and try again.";
    /** Lookups allowed in flight at once; a burst of adds beyond this is refused. */
    static final int MAX_PENDING_LOOKUPS = 5;

    /** Outcome of the latest YouTube handle lookup (or its "Looking up..." while pending); null = none yet. */
    private volatile String lastLookupMessage;
    private final java.util.concurrent.atomic.AtomicInteger pendingLookups = new java.util.concurrent.atomic.AtomicInteger();

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
            case ViewerEntry.Invalid i -> i.reply();
            case ViewerEntry.Twitch t -> apply(t.login(), null, allowList, add);
            case ViewerEntry.YouTubeChannel c -> apply(c.key(), null, allowList, add);
            case ViewerEntry.YouTubeHandle h -> {
                if (!add) {
                    Optional<String> known = host.config().youTubeKeyForLabel(h.handle());
                    if (known.isPresent()) yield apply(known.get(), null, allowList, false);
                }
                yield lookUp(h.handle(), allowList, add, onOutcome);
            }
        };
    }

    /** Starts the off-thread handle lookup; the reply is immediate and the outcome is reported when it ends. */
    private String lookUp(String handle, boolean allowList, boolean add, Consumer<String> onOutcome) {
        if (host.config().getYouTubeApiKey().isEmpty()) return NO_KEY_REPLY;
        if (pendingLookups.incrementAndGet() > MAX_PENDING_LOOKUPS) {
            pendingLookups.decrementAndGet();
            return TOO_MANY_REPLY;
        }
        String pending = "Looking up " + handle + " on YouTube...";
        lastLookupMessage = pending;
        java.util.concurrent.CompletableFuture<String> f;
        try {
            f = host.lookUpYouTubeChannel(handle);
        } catch (RuntimeException e) {
            pendingLookups.decrementAndGet();
            throw e;
        }
        f.whenComplete((channelId, err) -> {
            pendingLookups.decrementAndGet();
            String msg;
            try {
                if (err != null) {
                    Throwable cause = unwrap(err);
                    msg = lookupFailure(handle, cause);
                    host.logger().info("Sproutwatch: YouTube lookup for " + handle + " failed ("
                        + (cause instanceof YtException yt ? yt.kind() : cause.getClass().getSimpleName()) + ")");
                } else {
                    msg = apply(ViewerKey.youtube(channelId), handle, allowList, add);
                }
            } catch (RuntimeException e) {
                host.logger().info("Sproutwatch: YouTube lookup for " + handle + " failed (" + e.getClass().getSimpleName() + ")");
                msg = "YouTube lookup failed; try again.";
            }
            lastLookupMessage = msg;
            try {
                host.statusChanged();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Sproutwatch page refresh failed", e);
            }
            if (onOutcome != null) {
                try {
                    onOutcome.accept(msg);
                } catch (RuntimeException e) {
                    host.logger().log(Level.WARNING, "Sproutwatch lookup reply failed", e);
                }
            }
        });
        return pending;
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Reply for a failed lookup; never contains the key (YtException messages are not shown at all). */
    private static String lookupFailure(String handle, Throwable cause) {
        if (cause instanceof ActionsHost.NoYouTubeKey) return NO_KEY_REPLY;
        if (cause instanceof ActionsHost.LookupUnavailable) return UNAVAILABLE_REPLY;
        if (!(cause instanceof YtException yt)) return "YouTube lookup failed; try again.";
        return switch (yt.kind()) {
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
        SproutwatchConfig c = host.config();
        boolean youTube = ViewerKey.isYouTube(key);
        String list = allowList ? "allow list" : "ignore list";
        if (add) {
            boolean added = youTube && label != null
                ? c.addYouTubeEntry(allowList, key, label)
                : (allowList ? c.addAllow(key) : c.addIgnore(key));
            String shown = youTube ? c.entryDisplay(key) : key;
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
        String shown = youTube ? (label != null && c.youTubeLabel(key).isEmpty() ? label + " (YouTube)" : c.entryDisplay(key)) : key;
        boolean removed = allowList ? c.removeAllow(key) : c.removeIgnore(key);
        if (removed) host.saveConfig();
        return removed ? "Removed " + shown + " from the " + list + "." : shown + " is not on the " + list + ".";
    }

    public String selectPrefab(String raw) {
        String name = PenPrefabCatalog.normalize(raw);
        if (!PenPrefabCatalog.contains(name)) {
            return "Unknown pen prefab '" + (raw == null ? "" : raw) + "'. Available: "
                + String.join(", ", PenPrefabCatalog.names()) + ".";
        }
        host.config().setPenPrefab(name);
        host.saveConfig();
        return "Pen prefab set to " + name + ". Place the pen to paste it.";
    }

    public StatusSnapshot snapshot() {
        SproutwatchConfig c = host.config();
        World w = c.isPenSet() ? host.penWorld() : null;
        return new StatusSnapshot(
            host.listenerState(), host.listenerRunning(), host.feedAcked(),
            c.getTwitchChannel(), host.roster().size(), host.roster().queue().size(), c.getQueueCommand(),
            c.isAllowMode(), c.allowedLogins().size(), c.ignoredLogins().size(),
            host.registry().size(), c.getMaxSprouts(), host.registry().retiredLogins().size(),
            c.getTickSeconds(), c.getGraceSeconds(), c.getQuietSeconds(), host.tickerRunning(),
            c.isPersistSprouts(), c.isAutoStartOnBoot(),
            c.isPenSet(), c.getPenX(), c.getPenY(), c.getPenZ(), c.getPenSizeX(), c.getPenSizeZ(), c.getPenFacing(),
            w == null ? null : w.getName(), c.getPenWorld(),
            c.isChairSet(), c.getChairX(), c.getChairY(), c.getChairZ(),
            c.getPenPrefab(),
            host.sourceStates(), host.youTubeStatus());
    }

    /** The /sproutwatch status text. */
    public String statusReport() {
        return snapshot().report();
    }
}
