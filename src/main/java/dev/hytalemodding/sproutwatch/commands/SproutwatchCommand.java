package dev.hytalemodding.sproutwatch.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.SproutwatchPlugin;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenTicker;
import dev.hytalemodding.sproutwatch.ui.ActionsHost;
import dev.hytalemodding.sproutwatch.ui.ChatSourceCommands;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActions;
import dev.hytalemodding.sproutwatch.ui.SproutwatchSettingsPage;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SproutwatchCommand extends AbstractCommand {

    private final SproutwatchPlugin plugin;

    public SproutwatchCommand(SproutwatchPlugin plugin) {
        // AbstractCommand, not AbstractCommandCollection: the bare command opens the settings page
        // (the collection's final executeAsync only prints usage); subcommand dispatch is inherited.
        super("sproutwatch", "Open the Sproutwatch settings page; subcommands for everything else");
        this.plugin = plugin;
        String permission = plugin.getBasePermission() + ".admin";
        requirePermission(permission);
        addSubCommand(new ChannelCommand(plugin, permission));
        addSubCommand(new StartCommand(plugin, permission));
        addSubCommand(new StopCommand(plugin, permission));
        addSubCommand(new StatusCommand(plugin, permission));
        addSubCommand(new PlaceCommand(plugin, permission));
        addSubCommand(new ClearCommand(plugin, permission));
        addSubCommand(new CameraCommand(plugin, permission));
        addSubCommand(new IntervalCommand(plugin, permission));
        addSubCommand(new TestCommand(plugin, permission));
        addSubCommand(new AllowCommand(plugin, permission));
        addSubCommand(new IgnoreCommand(plugin, permission));
        addSubCommand(new QueueCommand(plugin, permission));
        addSubCommand(new PersistCommand(plugin, permission));
        addSubCommand(new SettingsCommand(plugin, permission));
        addSubCommand(new FilterCommand(plugin, permission));
        addSubCommand(new YouTubeCommand(plugin, permission));
        addSubCommand(new TwitchCommand(plugin, permission));
    }

    /** Bare {@code /sproutwatch}: same as {@code /sproutwatch settings}. */
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        openSettings(plugin, context);
        return CompletableFuture.completedFuture(null);
    }

    /** Opens the settings page for the sending player (console gets "Run this in game"). */
    static void openSettings(SproutwatchPlugin plugin, CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Run this in game as a player."));
            return;
        }
        PlayerRef sender = context.senderAs(PlayerRef.class);
        if (sender == null) {
            context.sendMessage(Message.raw("Could not resolve the sending player."));
            return;
        }
        // openCustomPage must run on the player's world thread (the page builds from there).
        ActionsHost.WorldQueue q = plugin.runOnPlayerWorld(sender, world -> {
            try {
                Ref<EntityStore> ref = sender.getReference();
                if (ref == null || !ref.isValid()) return;
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) return;
                player.getPageManager().openCustomPage(ref, store,
                    new SproutwatchSettingsPage(sender, plugin.getActions(), plugin.getOpenPages(), plugin.getBridgeLogger()));
            } catch (RuntimeException exception) {
                plugin.getBridgeLogger().log(Level.WARNING, "Sproutwatch settings page failed to open", exception);
                sender.sendMessage(Message.raw("Could not open the settings page: " + exception.getMessage() + " (see server log)"));
            }
        });
        switch (q) {
            case QUEUED -> { }
            case NOT_LOADED -> context.sendMessage(Message.raw("Your world is not loaded."));
            case REJECTED -> context.sendMessage(Message.raw("Your world is unloading; try again."));
        }
    }

    /** AbstractCommand.execute returns CompletableFuture<Void>; funnel subclasses through run(). */
    abstract static class Sub extends AbstractCommand {
        final SproutwatchPlugin plugin;

        Sub(SproutwatchPlugin plugin, String name, String desc, String permission) {
            super(name, desc);
            this.plugin = plugin;
            requirePermission(permission);
        }

        /** Usage variant (no name): dispatched when the positional token count matches its required args. */
        Sub(SproutwatchPlugin plugin, String desc, String permission) {
            super(desc);
            this.plugin = plugin;
            requirePermission(permission);
        }

        void reply(CommandContext context, String text) {
            context.sendMessage(Message.raw(text));
        }

        SproutwatchConfig config() {
            return plugin.getConfigHolder().get();
        }

        /**
         * Where a YouTube handle lookup's outcome goes once it ends (on the lookup thread): a player
         * gets it as a chat message from their world thread, like place's result (straight to the context
         * when that world is not loaded or rejects the task); the console directly.
         */
        Consumer<String> laterReply(CommandContext context) {
            PlayerRef sender = context.isPlayer() ? context.senderAs(PlayerRef.class) : null;
            if (sender == null) return text -> context.sendMessage(Message.raw(text));
            return text -> {
                ActionsHost.WorldQueue q = plugin.runOnPlayerWorld(sender, world -> sender.sendMessage(Message.raw(text)));
                if (q != ActionsHost.WorldQueue.QUEUED) context.sendMessage(Message.raw(text));   // world gone: best effort
            };
        }

        /** List entries as the streamer reads them: Twitch logins, "@handle (YouTube)" for YouTube keys. */
        String displayList(Set<String> keys) {
            SproutwatchConfig c = config();
            return keys.stream().map(c::entryDisplay).collect(Collectors.joining(", "));
        }

        SproutwatchActions actions() {
            return plugin.getActions();
        }

        /** The sending player, or null (with a reply) when run from console. */
        PlayerRef player(CommandContext context) {
            if (!context.isPlayer()) {
                reply(context, "Run this in game as a player.");
                return null;
            }
            PlayerRef player = context.senderAs(PlayerRef.class);
            if (player == null) reply(context, "Could not resolve the sending player.");
            return player;
        }

        abstract void run(CommandContext context);

        @Override
        protected final CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            run(context);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ChannelCommand extends Sub {
        private final RequiredArg<String> nameArg;

        ChannelCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "channel", "Set the Twitch channel to watch", permission);
            nameArg = withRequiredArg("name", "Twitch channel name", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().setChannel(context.get(nameArg)));
        }
    }

    private static final class StartCommand extends Sub {
        StartCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "start", "Start watching chat and filling the pen", permission);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().startListener());
        }
    }

    private static final class StopCommand extends Sub {
        StopCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "stop", "Stop watching chat (sprouts stay until clear)", permission);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().stopListener());
        }
    }

    private static final class StatusCommand extends Sub {
        StatusCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "status", "Show listener, roster and pen status", permission);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().statusReport());
        }
    }

    private static final class PlaceCommand extends Sub {
        PlaceCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "place", "Paste the pen prefab centered on you and save it", permission);
        }

        @Override void run(CommandContext context) {
            PlayerRef sender = player(context);
            if (sender == null) return;
            reply(context, actions().place(sender));
        }
    }

    private static final class ClearCommand extends Sub {
        ClearCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "clear", "Remove every sprout in the pen", permission);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().clear());
        }
    }

    private static final class CameraCommand extends Sub {
        CameraCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "camera", "Toggle the pen camera for you; /sproutwatch camera <height> <back> <fov> to tune", permission);
            addUsageVariant(new CameraTuneVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            PlayerRef sender = player(context);
            if (sender == null) return;
            if (!config().isPenSet()) {
                reply(context, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            boolean on = plugin.getCameraService().toggleManual(sender);
            reply(context, on ? "Pen camera on. Run /sproutwatch camera again to reset." : "Pen camera off.");
        }
    }

    /** {@code /sproutwatch camera <height> <back> <fov>}: console may tune, so no player requirement. */
    private static final class CameraTuneVariant extends Sub {
        private final RequiredArg<Double> heightArg;
        private final RequiredArg<Double> backArg;
        private final RequiredArg<Double> fovArg;

        CameraTuneVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Set camera height, back and fov (blocks, blocks, degrees) and re-send it to anyone using it", permission);
            heightArg = withRequiredArg("height", "Blocks above the floor", ArgTypes.DOUBLE);
            backArg = withRequiredArg("back", "Blocks behind the short side", ArgTypes.DOUBLE);
            fovArg = withRequiredArg("fov", "Field of view in degrees", ArgTypes.DOUBLE);
        }

        @Override void run(CommandContext context) {
            SproutwatchConfig c = config();
            if (!c.isPenSet()) {
                reply(context, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            c.setCamera(context.get(heightArg), context.get(backArg), context.get(fovArg));
            plugin.saveConfig();
            plugin.getCameraService().refresh(Universe.get().getPlayers());
            reply(context, "Camera height " + c.getCameraHeight() + ", back " + c.getCameraBack() + ", fov " + c.getCameraFov() + ".");
        }
    }

    private static final class IntervalCommand extends Sub {
        private final RequiredArg<Integer> secondsArg;

        IntervalCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "interval", "Seconds between sprout spawns (min 5)", permission);
            secondsArg = withRequiredArg("seconds", "Tick interval in seconds", ArgTypes.INTEGER);
        }

        @Override void run(CommandContext context) {
            reply(context, actions().setTickSeconds(context.get(secondsArg)));
        }
    }

    private static final class TestCommand extends Sub {
        private final RequiredArg<String> loginArg;

        TestCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "test", "Fake viewers: /sproutwatch test <user> [now|remove] | test list", permission);
            loginArg = withRequiredArg("user", "Fake viewer user name", ArgTypes.STRING);
            addUsageVariant(new TestNowVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            String login = context.get(loginArg);
            if ("list".equalsIgnoreCase(login)) {
                Set<String> guests = plugin.getRoster().guests();
                reply(context, guests.isEmpty()
                    ? "No test viewers. Add one with /sproutwatch test <user> [now]."
                    : "Test viewers (" + guests.size() + "): " + String.join(", ", new java.util.TreeSet<>(guests))
                        + ". They give up their spot first when the pen is full; Clear or 'test <user> remove' drops them.");
                return;
            }
            applyTest(plugin, this, context, login, false);
        }
    }

    /** {@code /sproutwatch test <user> now|remove}: forced tick, or drop the test viewer. */
    private static final class TestNowVariant extends Sub {
        private final RequiredArg<String> loginArg;
        private final RequiredArg<String> whenArg;

        TestNowVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Pretend <user> is in chat and tick immediately, or remove a test viewer", permission);
            loginArg = withRequiredArg("user", "Fake viewer user name", ArgTypes.STRING);
            whenArg = withRequiredArg("when", "'now' or 'remove'", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            String when = context.get(whenArg);
            if ("remove".equalsIgnoreCase(when)) {
                reply(context, actions().removeGuest(context.get(loginArg)));
                return;
            }
            if (!"now".equalsIgnoreCase(when)) {
                reply(context, "Usage: /sproutwatch test <user> [now|remove] | test list");
                return;
            }
            applyTest(plugin, this, context, context.get(loginArg), true);
        }
    }

    /** Shared body of {@code test} and its {@code now} variant. */
    static void applyTest(SproutwatchPlugin plugin, Sub subcommand, CommandContext context, String rawLogin, boolean now) {
        if (!subcommand.config().isPenSet()) {
            subcommand.reply(context, "No pen placed yet. Run /sproutwatch place first.");
            return;
        }
        String login = rawLogin == null ? "" : SproutwatchConfig.normalizeChannel(rawLogin);
        if (login.isEmpty()) {
            subcommand.reply(context, "Give a user name, e.g. /sproutwatch test alice now");
            return;
        }
        if (subcommand.config().ignoredViewers().contains(login)) {
            subcommand.reply(context, login + " is on the ignore list (or is the channel itself); remove them first.");
            return;
        }
        if (!subcommand.config().passesFilter(login)) {
            subcommand.reply(context, login + " is not on the allow list; /sproutwatch allow add " + login + " or /sproutwatch filter ignore.");
            return;
        }
        if (plugin.getRegistry().isRetired(login)) {
            subcommand.reply(context, login + " was retired (a player killed their sprout); they get one again after leaving and rejoining chat, or after /sproutwatch clear.");
            return;
        }
        // firstSeen 0L: PenReconciler spawns the smallest firstSeen first, so the test login jumps ahead of
        // every real viewer in first-seen order (an existing entry keeps its time; putIfAbsent). The
        // "!sprout" queue is deliberately left alone: SproutQueue has no move-to-front, and offering the
        // test login would only put it behind real queued viewers anyway, so queued real viewers spawn
        // before a test viewer. Empty text: never mistaken for the queue command.
        plugin.getRoster().addGuest(login, 0L);
        if (now) {
            if (plugin.getRegistry().contains(login)) {
                subcommand.reply(context, login + " is already in the pen.");
                return;
            }
            int size = plugin.getRegistry().size();
            int max = subcommand.config().getMaxSprouts();
            if (size >= max) {
                subcommand.reply(context, "Pen is full (" + size + "/" + max + "); run /sproutwatch clear or raise MaxSprouts.");
                return;
            }
            if (PenTicker.resolveWorld(subcommand.config()) == null) {
                subcommand.reply(context, "Pen world is not loaded; nothing to tick.");
                return;
            }
            // The tick runs later on the pen world thread; the spawner fails quietly when the pen has no
            // validated free spot, so the real outcome comes back as a second line (console: the log).
            PlayerRef sender = context.isPlayer() ? context.senderAs(PlayerRef.class) : null;
            plugin.getTicker().tickNow(outcome -> {
                String text = outcome.testReply(login);
                if (sender != null) sender.sendMessage(Message.raw(text));
                else plugin.getBridgeLogger().info("Sproutwatch: " + text);
            });
            subcommand.reply(context, login + " added as a test viewer; ticking now.");
        } else if (plugin.getTicker().isRunning()) {
            subcommand.reply(context, login + " added as a test viewer; spawns on the next tick.");
        } else {
            subcommand.reply(context, login + " added as a test viewer; run /sproutwatch start (or add 'now').");
        }
    }

    /** {@code /sproutwatch filter [allow|ignore]}: which list decides eligibility. */
    private static final class FilterCommand extends Sub {
        FilterCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "filter", "Viewer filter: /sproutwatch filter [allow|ignore]", permission);
            addUsageVariant(new FilterSetVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            reply(context, actions().snapshot().filterLine());
        }
    }

    private static final class FilterSetVariant extends Sub {
        private final RequiredArg<String> modeArg;

        FilterSetVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Set the viewer filter to the allow or the ignore list", permission);
            modeArg = withRequiredArg("mode", "allow | ignore", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            String mode = context.get(modeArg);
            if ("allow".equalsIgnoreCase(mode)) reply(context, actions().setFilter(true));
            else if ("ignore".equalsIgnoreCase(mode)) reply(context, actions().setFilter(false));
            else reply(context, "Usage: /sproutwatch filter [allow|ignore]");
        }
    }

    /** {@code /sproutwatch allow list | add <user> | remove <user>}: AllowUsers (used in allow mode). */
    private static final class AllowCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch allow list | add <user> | remove <user>";
        private final RequiredArg<String> actionArg;

        AllowCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "allow", "Allow list: /sproutwatch allow list | add <user> | remove <user> (applies in allow mode)", permission);
            actionArg = withRequiredArg("action", "list", ArgTypes.STRING);
            addUsageVariant(new AllowEditVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            if (!"list".equalsIgnoreCase(context.get(actionArg))) {
                reply(context, USAGE);
                return;
            }
            Set<String> allow = config().allowedViewers();
            reply(context, allow.isEmpty()
                ? (config().isAllowMode() ? "Allow list is empty: nobody gets a sprout until you add someone." : "Allow list is empty (not in use: filter is the ignore list).")
                : "Allow list (" + allow.size() + "): " + displayList(allow));
        }
    }

    /** {@code /sproutwatch allow add|remove <user>}. */
    private static final class AllowEditVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> viewerArg;

        AllowEditVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Add or remove a user on the allow list", permission);
            actionArg = withRequiredArg("action", "add or remove", ArgTypes.STRING);
            viewerArg = withRequiredArg("user", "Twitch name, or yt:@handle / YouTube link / UC... ID", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            String action = context.get(actionArg);
            String viewer = context.get(viewerArg);
            if ("add".equalsIgnoreCase(action)) {
                reply(context, actions().addAllow(viewer, laterReply(context)));
            } else if ("remove".equalsIgnoreCase(action)) {
                reply(context, actions().removeAllow(viewer, laterReply(context)));
            } else {
                reply(context, AllowCommand.USAGE);
            }
        }
    }

    /** {@code /sproutwatch ignore list | add <user> | remove <user>}: IgnoreUsers (read live by the roster). */
    private static final class IgnoreCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch ignore list | add <user> | remove <user>";
        private final RequiredArg<String> actionArg;

        IgnoreCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "ignore", "Ignore list: /sproutwatch ignore list | add <user> | remove <user>", permission);
            actionArg = withRequiredArg("action", "list", ArgTypes.STRING);
            addUsageVariant(new IgnoreEditVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            if (!"list".equalsIgnoreCase(context.get(actionArg))) {
                reply(context, USAGE);
                return;
            }
            // ignoredViewers() always includes the channel login itself (the streamer never gets a sprout).
            Set<String> ignored = config().ignoredViewers();
            reply(context, ignored.isEmpty()
                ? "Ignore list is empty."
                : "Ignore list (" + ignored.size() + ", includes the channel): " + displayList(ignored));
        }
    }

    /** {@code /sproutwatch ignore add|remove <user>}. */
    private static final class IgnoreEditVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> viewerArg;

        IgnoreEditVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Add or remove a user on the ignore list", permission);
            actionArg = withRequiredArg("action", "add or remove", ArgTypes.STRING);
            viewerArg = withRequiredArg("user", "Twitch name, or yt:@handle / YouTube link / UC... ID", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            String action = context.get(actionArg);
            String viewer = context.get(viewerArg);
            if ("add".equalsIgnoreCase(action)) {
                reply(context, actions().addIgnore(viewer, laterReply(context)));
            } else if ("remove".equalsIgnoreCase(action)) {
                reply(context, actions().removeIgnore(viewer, laterReply(context)));
            } else {
                reply(context, IgnoreCommand.USAGE);
            }
        }
    }

    /** {@code /sproutwatch queue list | clear | remove <user>}: the "!sprout" priority queue (in memory). */
    private static final class QueueCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch queue list | clear | remove <user>";
        private final RequiredArg<String> actionArg;

        QueueCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "queue", "!sprout queue: /sproutwatch queue list | clear | remove <user>", permission);
            actionArg = withRequiredArg("action", "list or clear", ArgTypes.STRING);
            addUsageVariant(new QueueRemoveVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            String action = context.get(actionArg);
            if ("list".equalsIgnoreCase(action)) {
                List<String> q = plugin.getQueue().snapshot();
                reply(context, q.isEmpty() ? "Queue is empty." : "Queue (" + q.size() + "): " + String.join(", ", q));
            } else if ("clear".equalsIgnoreCase(action)) {
                int n = plugin.getQueue().size();
                plugin.getQueue().clear();
                reply(context, "Queue cleared (" + n + " removed).");
            } else {
                reply(context, USAGE);
            }
        }
    }

    /** {@code /sproutwatch queue remove <user>}. */
    private static final class QueueRemoveVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> loginArg;

        QueueRemoveVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Drop a user from the !sprout queue", permission);
            actionArg = withRequiredArg("action", "remove", ArgTypes.STRING);
            loginArg = withRequiredArg("user", "Twitch user name", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            if (!"remove".equalsIgnoreCase(context.get(actionArg))) {
                reply(context, QueueCommand.USAGE);
                return;
            }
            String login = SproutwatchConfig.normalizeChannel(context.get(loginArg));
            if (login.isEmpty()) {
                reply(context, "Invalid user name.");
                return;
            }
            boolean removed = plugin.getQueue().remove(login);
            reply(context, removed ? "Removed " + login + " from the queue." : login + " is not queued.");
        }
    }

    /**
     * {@code /sproutwatch persist [on|off]}: PersistSprouts. On, sprouts stay after their viewer
     * leaves chat and the longest-gone one is replaced when the pen is at MaxSprouts; off, sprouts
     * despawn GraceSeconds after their viewer leaves. No argument toggles.
     */
    private static final class PersistCommand extends Sub {
        static final String USAGE = "Usage: /sproutwatch persist [on|off]";

        PersistCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "persist", "Keep sprouts after their viewer leaves: /sproutwatch persist [on|off]", permission);
            addUsageVariant(new PersistSetVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            reply(context, actions().setPersist(!config().isPersistSprouts()));
        }
    }

    /** {@code /sproutwatch persist on|off}: set explicitly. */
    private static final class PersistSetVariant extends Sub {
        private final RequiredArg<String> stateArg;

        PersistSetVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Turn persist on or off", permission);
            stateArg = withRequiredArg("state", "on or off", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            String state = context.get(stateArg);
            if ("on".equalsIgnoreCase(state)) {
                reply(context, actions().setPersist(true));
            } else if ("off".equalsIgnoreCase(state)) {
                reply(context, actions().setPersist(false));
            } else {
                reply(context, PersistCommand.USAGE);
            }
        }
    }

    /**
     * {@code /sproutwatch youtube [on|off | handle <h> | key <k> | video <link|clear> | hours <n>]}; parsing
     * and replies in ChatSourceCommands (the key is only ever echoed masked). The engine's
     * CommandManager logs every command's full text ({@code <player> executed command: ...}), so
     * {@code youtube key} puts the key in the server log; the Connect tab's key field does not, and
     * the description says so. Ops: never run the server with JDK HttpClient header logging
     * ({@code -Djdk.httpclient.HttpClient.log=headers|all}) while a key is configured: the key is sent
     * in the X-Goog-Api-Key header.
     */
    private static final class YouTubeCommand extends Sub {
        YouTubeCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "youtube", "YouTube chat: /sproutwatch youtube on|off | handle <@handle> | key <key> | video <link|clear> | hours <1-24>"
                + " (the settings page is safer for the key: command text may appear in the server log)", permission);
            addUsageVariant(new YouTubeToggleVariant(plugin, permission));
            addUsageVariant(new YouTubeSetVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            reply(context, ChatSourceCommands.youTubeSummary(actions().snapshot()));
        }
    }

    /** {@code /sproutwatch youtube on|off}. */
    private static final class YouTubeToggleVariant extends Sub {
        private final RequiredArg<String> stateArg;

        YouTubeToggleVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Turn YouTube chat on or off", permission);
            stateArg = withRequiredArg("state", "on or off", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            reply(context, ChatSourceCommands.youTube(actions(), context.get(stateArg)));
        }
    }

    /** {@code /sproutwatch youtube handle|key|video|hours <value>}. */
    private static final class YouTubeSetVariant extends Sub {
        private final RequiredArg<String> settingArg;
        private final RequiredArg<String> valueArg;

        YouTubeSetVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Set the YouTube handle, API key (safer on the settings page: command text may appear in the server log),"
                + " stream link (or 'clear') or stream hours", permission);
            settingArg = withRequiredArg("setting", "handle, key, video or hours", ArgTypes.STRING);
            valueArg = withRequiredArg("value", "@handle, API key, stream link or 'clear', hours 1-24", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            reply(context, ChatSourceCommands.youTube(actions(), context.get(settingArg), context.get(valueArg)));
        }
    }

    /** {@code /sproutwatch twitch [on|off]}: TwitchEnabled. */
    private static final class TwitchCommand extends Sub {
        TwitchCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "twitch", "Twitch chat: /sproutwatch twitch on|off", permission);
            addUsageVariant(new TwitchToggleVariant(plugin, permission));
        }

        @Override void run(CommandContext context) {
            reply(context, ChatSourceCommands.twitchSummary(config().isTwitchEnabled()));
        }
    }

    /** {@code /sproutwatch twitch on|off}. */
    private static final class TwitchToggleVariant extends Sub {
        private final RequiredArg<String> stateArg;

        TwitchToggleVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Turn Twitch chat on or off", permission);
            stateArg = withRequiredArg("state", "on or off", ArgTypes.STRING);
        }

        @Override void run(CommandContext context) {
            reply(context, ChatSourceCommands.twitch(actions(), context.get(stateArg)));
        }
    }

    /** {@code /sproutwatch settings}: opens the settings page for the sending player. */
    private static final class SettingsCommand extends Sub {
        SettingsCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "settings", "Open the Sproutwatch settings page", permission);
        }

        @Override void run(CommandContext context) {
            openSettings(plugin, context);
        }
    }
}
