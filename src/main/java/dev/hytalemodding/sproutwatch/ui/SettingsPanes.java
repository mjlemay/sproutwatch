package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import dev.hytalemodding.sproutwatch.config.CreaturePreset;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills the settings page's panes on every build: field values and event bindings per tab. Pure
 * builder code with no page state; the page owns lifecycle, the live Details labels and the
 * message line. Every selector here is an id in SettingsPage.ui / ListEntryRow.ui.
 */
final class SettingsPanes {

    static final String LIST_ROW = "Pages/Sproutwatch/ListEntryRow.ui";

    private SettingsPanes() {}

    /** A saved field shows a red Remove (clears it) in place of Save; an empty one shows Save. */
    private static void saveOrRemove(UICommandBuilder commands, UIEventBuilder events, String name, boolean saved, PageAction removeAction) {
        commands.set("#Save" + name + "Button.Visible", !saved);
        commands.set("#Remove" + name + "Button.Visible", saved);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Remove" + name + "Button",
            new EventData().append(SettingsEvent.KEY_ACTION, removeAction.wireName()));
    }

    /**
     * Connect tab: chat sources, Twitch channel and YouTube setup, plus the Begin binding (its button
     * sits on the Pen tab: start the listener and close the page). The API key field is always sent empty: the key never travels back to the client,
     * only its masked form as the field's placeholder.
     */
    static void connect(UICommandBuilder commands, UIEventBuilder events, SproutwatchConfig config, StatusSnapshot snapshot) {
        commands.set("#ChannelField.Value", config.getTwitchChannel());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveChannelButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_CHANNEL.wireName()).append(SettingsEvent.KEY_CHANNEL, "#ChannelField.Value"));
        saveOrRemove(commands, events, "Channel", !config.getTwitchChannel().isEmpty(), PageAction.REMOVE_CHANNEL);

        commands.set("#TwitchCheck.Value", config.isTwitchEnabled());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TwitchCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_TWITCH_ENABLED.wireName()).append(SettingsEvent.KEY_TWITCH_ON, "#TwitchCheck.Value"), false);
        commands.set("#YouTubeCheck.Value", config.isYouTubeEnabled());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#YouTubeCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_YOUTUBE_ENABLED.wireName()).append(SettingsEvent.KEY_YOUTUBE_ON, "#YouTubeCheck.Value"), false);

        commands.set("#YouTubeHandleField.Value", config.getYouTubeHandle());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeHandleButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_YOUTUBE_HANDLE.wireName()).append(SettingsEvent.KEY_YOUTUBE_HANDLE, "#YouTubeHandleField.Value"));
        saveOrRemove(commands, events, "YouTubeHandle", !config.getYouTubeHandle().isEmpty(), PageAction.REMOVE_YOUTUBE_HANDLE);
        commands.set("#YouTubeKeyField.Value", "");
        commands.set("#YouTubeKeyField.PlaceholderText", keyPlaceholder(snapshot.youTube())); // plain String: an inline-literal PlaceholderText is a String on the client; a Message here disconnects the player
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeKeyButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_YOUTUBE_KEY.wireName()).append(SettingsEvent.KEY_YOUTUBE_KEY, "#YouTubeKeyField.Value"));
        saveOrRemove(commands, events, "YouTubeKey", !config.getYouTubeApiKey().isEmpty(), PageAction.REMOVE_YOUTUBE_KEY);
        commands.set("#YouTubeVideoField.Value", config.getYouTubeVideo());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeVideoButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_YOUTUBE_VIDEO.wireName()).append(SettingsEvent.KEY_YOUTUBE_VIDEO, "#YouTubeVideoField.Value"));
        saveOrRemove(commands, events, "YouTubeVideo", !config.getYouTubeVideo().isEmpty(), PageAction.REMOVE_YOUTUBE_VIDEO);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#BeginButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.BEGIN.wireName()));
    }

    /** Placeholder of the (always empty) API key field: "API key" when none is saved, else only the masked key. */
    static String keyPlaceholder(YouTubeStatus y) {
        String masked = y.maskedKey();
        if (masked == null || masked.equals("not set")) return "API key";
        String shown = masked.equals("set") ? "API key" : masked;
        return shown + " (saved; paste a new key to replace it)";
    }

    /** Viewers tab: the filter toggle (tab-styled twin buttons) and only the list that is in use. */
    static void viewers(UICommandBuilder commands, UIEventBuilder events, SproutwatchConfig config, StatusSnapshot snapshot) {
        boolean allowMode = config.isAllowMode();
        commands.set("#FilterIgnoreButton.Style", allowMode ? SettingsTab.STYLE : SettingsTab.SELECTED_STYLE);
        commands.set("#FilterAllowButton.Style", allowMode ? SettingsTab.SELECTED_STYLE : SettingsTab.STYLE);
        commands.set("#FilterLabel.Text", snapshot.filterLabel());
        commands.set("#AllowSection.Visible", allowMode);
        commands.set("#IgnoreSection.Visible", !allowMode);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterIgnoreButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_FILTER.wireName()).append(SettingsEvent.KEY_FILTER, "ignore"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterAllowButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_FILTER.wireName()).append(SettingsEvent.KEY_FILTER, "allow"));

        List<String> allow = List.copyOf(config.allowedViewers());
        commands.set("#AllowEmptyLabel.Visible", allow.isEmpty());
        int i = 0;
        for (String viewerKey : allow) {
            String rowSelector = "#AllowList[" + i + "]";
            commands.append("#AllowList", LIST_ROW);
            commands.set(rowSelector + " #Login.Text", config.entryDisplay(viewerKey));   // "@x (YouTube)"; Remove still sends the raw key
            events.addEventBinding(CustomUIEventBindingType.Activating, rowSelector + " #RemoveButton",
                new EventData().append(SettingsEvent.KEY_ACTION, PageAction.REMOVE_ALLOW.wireName()).append(SettingsEvent.KEY_VIEWER_KEY, viewerKey));
            i++;
        }
        commands.set("#AllowField.Value", "");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddAllowButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.ADD_ALLOW.wireName()).append(SettingsEvent.KEY_ALLOW_INPUT, "#AllowField.Value"));

        // ignoredViewers() always ends with the channel login; that row is fixed (no Remove button).
        String channel = config.getTwitchChannel();
        i = 0;
        for (String viewerKey : config.ignoredViewers()) {
            String rowSelector = "#IgnoreList[" + i + "]";
            boolean fixed = viewerKey.equals(channel);
            commands.append("#IgnoreList", LIST_ROW);
            commands.set(rowSelector + " #Login.Text", fixed ? viewerKey + " (the channel)" : config.entryDisplay(viewerKey));
            commands.set(rowSelector + " #RemoveButton.Visible", !fixed);
            if (!fixed) {
                events.addEventBinding(CustomUIEventBindingType.Activating, rowSelector + " #RemoveButton",
                    new EventData().append(SettingsEvent.KEY_ACTION, PageAction.REMOVE_IGNORE.wireName()).append(SettingsEvent.KEY_VIEWER_KEY, viewerKey));
            }
            i++;
        }
        commands.set("#IgnoreField.Value", "");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddIgnoreButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.ADD_IGNORE.wireName()).append(SettingsEvent.KEY_IGNORE_INPUT, "#IgnoreField.Value"));
    }

    /** Listener tab: Start / Connecting / Stop buttons (visibility pushed live by the page), persist, auto-start, tick interval. */
    static void listener(UICommandBuilder commands, UIEventBuilder events, StatusSnapshot snapshot) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.START_LISTENER.wireName()));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.STOP_LISTENER.wireName()));
        // Connecting / retrying: the gray button cancels (stops) so a stuck retry loop can be ended here.
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConnectingButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.STOP_LISTENER.wireName()));
        commands.set("#PersistCheck.Value", snapshot.persist());
        commands.set("#PersistCheckLabel.Text", snapshot.persistLabel());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PersistCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_PERSIST.wireName()).append(SettingsEvent.KEY_PERSIST, "#PersistCheck.Value"), false);
        commands.set("#AutoStartCheck.Value", snapshot.autoStart());
        commands.set("#AutoStartCheckLabel.Text", snapshot.autoStartLabel());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AutoStartCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SET_AUTO_START.wireName()).append(SettingsEvent.KEY_AUTO_START, "#AutoStartCheck.Value"), false);
        commands.set("#IntervalField.Value", snapshot.tickSeconds());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveIntervalButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_INTERVAL.wireName()).append(SettingsEvent.KEY_INTERVAL, "#IntervalField.Value"));
    }

    /** Pen tab: creatures dropdown, prefab dropdown, Place, Clear, max sprouts. */
    static void pen(UICommandBuilder commands, UIEventBuilder events, StatusSnapshot snapshot, SproutwatchConfig config) {
        commands.set("#MaxField.Value", config.getMaxSprouts());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveMaxButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SAVE_MAX.wireName()).append(SettingsEvent.KEY_MAX, "#MaxField.Value"));
        List<DropdownEntryInfo> creatures = new ArrayList<>();
        for (CreaturePreset p : CreaturePreset.values()) {
            creatures.add(new DropdownEntryInfo(LocalizableString.fromString(p.displayName()), p.id()));
        }
        commands.set("#CreatureDropdown.Entries", creatures);
        commands.set("#CreatureDropdown.Value", config.getCreatures());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CreatureDropdown",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SELECT_CREATURES.wireName()).append(SettingsEvent.KEY_CREATURES, "#CreatureDropdown.Value"), false);

        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (String name : PenPrefabCatalog.names()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(PenPrefabCatalog.labelFor(name)), name));
        }
        commands.set("#PrefabDropdown.Entries", entries);
        // Effective name: a stale config value that is no longer in the catalog would otherwise leave
        // the dropdown unselected, so fall back to the catalog default (what PenPlacer will use too).
        String selected = PenPrefabCatalog.contains(snapshot.prefabName()) ? snapshot.prefabName() : PenPrefabCatalog.DEFAULT;
        commands.set("#PrefabDropdown.Value", selected);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PrefabDropdown",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.SELECT_PREFAB.wireName()).append(SettingsEvent.KEY_PREFAB, "#PrefabDropdown.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PlaceButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.PLACE.wireName()));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemovePenButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.REMOVE_PEN.wireName()));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearButton",
            new EventData().append(SettingsEvent.KEY_ACTION, PageAction.CLEAR.wireName()));
    }
}
