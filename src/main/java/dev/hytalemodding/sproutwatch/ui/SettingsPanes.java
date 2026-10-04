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
    private static void saveOrRemove(UICommandBuilder cmd, UIEventBuilder evt, String name, boolean saved, String removeAction) {
        cmd.set("#Save" + name + "Button.Visible", !saved);
        cmd.set("#Remove" + name + "Button.Visible", saved);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Remove" + name + "Button",
            new EventData().append(SettingsEvent.KEY_ACTION, removeAction));
    }

    /**
     * Connect tab: chat sources, channel, YouTube setup and cap, plus the Begin binding (its button
     * sits on the Pen tab: start the listener and close the page). The API key field is always sent empty: the key never travels back to the client,
     * only its masked form as the field's placeholder.
     */
    static void connect(UICommandBuilder cmd, UIEventBuilder evt, SproutwatchConfig cfg, StatusSnapshot s) {
        cmd.set("#ChannelField.Value", cfg.getTwitchChannel());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveChannelButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveChannel").append(SettingsEvent.KEY_CHANNEL, "#ChannelField.Value"));
        saveOrRemove(cmd, evt, "Channel", !cfg.getTwitchChannel().isEmpty(), "removeChannel");

        cmd.set("#TwitchCheck.Value", cfg.isTwitchEnabled());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TwitchCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "setTwitchEnabled").append(SettingsEvent.KEY_TWITCH_ON, "#TwitchCheck.Value"), false);
        cmd.set("#YouTubeCheck.Value", cfg.isYouTubeEnabled());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#YouTubeCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "setYouTubeEnabled").append(SettingsEvent.KEY_YOUTUBE_ON, "#YouTubeCheck.Value"), false);

        cmd.set("#YouTubeHandleField.Value", cfg.getYouTubeHandle());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeHandleButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveYouTubeHandle").append(SettingsEvent.KEY_YOUTUBE_HANDLE, "#YouTubeHandleField.Value"));
        saveOrRemove(cmd, evt, "YouTubeHandle", !cfg.getYouTubeHandle().isEmpty(), "removeYouTubeHandle");
        cmd.set("#YouTubeKeyField.Value", "");
        cmd.set("#YouTubeKeyField.PlaceholderText", keyPlaceholder(s.youTube())); // plain String: an inline-literal PlaceholderText is a String on the client; a Message here disconnects the player
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeKeyButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveYouTubeKey").append(SettingsEvent.KEY_YOUTUBE_KEY, "#YouTubeKeyField.Value"));
        saveOrRemove(cmd, evt, "YouTubeKey", !cfg.getYouTubeApiKey().isEmpty(), "removeYouTubeKey");
        cmd.set("#YouTubeVideoField.Value", cfg.getYouTubeVideo());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveYouTubeVideoButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveYouTubeVideo").append(SettingsEvent.KEY_YOUTUBE_VIDEO, "#YouTubeVideoField.Value"));
        saveOrRemove(cmd, evt, "YouTubeVideo", !cfg.getYouTubeVideo().isEmpty(), "removeYouTubeVideo");

        cmd.set("#MaxField.Value", cfg.getMaxSprouts());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveMaxButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveMax").append(SettingsEvent.KEY_MAX, "#MaxField.Value"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#BeginButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "begin"));
    }

    /** Placeholder of the (always empty) API key field: "API key" when none is saved, else only the masked key. */
    static String keyPlaceholder(YouTubeStatus y) {
        String masked = y.maskedKey();
        if (masked == null || masked.equals("not set")) return "API key";
        String shown = masked.equals("set") ? "API key" : masked;
        return shown + " (saved; paste a new key to replace it)";
    }

    /** Viewers tab: the filter toggle (tab-styled twin buttons) and only the list that is in use. */
    static void viewers(UICommandBuilder cmd, UIEventBuilder evt, SproutwatchConfig cfg, StatusSnapshot s) {
        boolean allowMode = cfg.isAllowMode();
        cmd.set("#FilterIgnoreButton.Style", allowMode ? SettingsTab.STYLE : SettingsTab.SELECTED_STYLE);
        cmd.set("#FilterAllowButton.Style", allowMode ? SettingsTab.SELECTED_STYLE : SettingsTab.STYLE);
        cmd.set("#FilterLabel.Text", s.filterLabel());
        cmd.set("#AllowSection.Visible", allowMode);
        cmd.set("#IgnoreSection.Visible", !allowMode);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#FilterIgnoreButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "setFilter").append(SettingsEvent.KEY_FILTER, "ignore"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#FilterAllowButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "setFilter").append(SettingsEvent.KEY_FILTER, "allow"));

        List<String> allow = List.copyOf(cfg.allowedLogins());
        cmd.set("#AllowEmptyLabel.Visible", allow.isEmpty());
        int i = 0;
        for (String login : allow) {
            String sel = "#AllowList[" + i + "]";
            cmd.append("#AllowList", LIST_ROW);
            cmd.set(sel + " #Login.Text", cfg.entryDisplay(login));   // "@x (YouTube)"; Remove still sends the raw key
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                new EventData().append(SettingsEvent.KEY_ACTION, "removeAllow").append(SettingsEvent.KEY_LOGIN, login));
            i++;
        }
        cmd.set("#AllowField.Value", "");
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AddAllowButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "addAllow").append(SettingsEvent.KEY_ALLOW_INPUT, "#AllowField.Value"));

        // ignoredLogins() always ends with the channel login; that row is fixed (no Remove button).
        String channel = cfg.getTwitchChannel();
        i = 0;
        for (String login : cfg.ignoredLogins()) {
            String sel = "#IgnoreList[" + i + "]";
            boolean fixed = login.equals(channel);
            cmd.append("#IgnoreList", LIST_ROW);
            cmd.set(sel + " #Login.Text", fixed ? login + " (the channel)" : cfg.entryDisplay(login));
            cmd.set(sel + " #RemoveButton.Visible", !fixed);
            if (!fixed) {
                evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                    new EventData().append(SettingsEvent.KEY_ACTION, "removeIgnore").append(SettingsEvent.KEY_LOGIN, login));
            }
            i++;
        }
        cmd.set("#IgnoreField.Value", "");
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AddIgnoreButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "addIgnore").append(SettingsEvent.KEY_IGNORE_INPUT, "#IgnoreField.Value"));
    }

    /** Listener tab: Start / Connecting / Stop buttons (visibility pushed live by the page), persist, auto-start, tick interval. */
    static void listener(UICommandBuilder cmd, UIEventBuilder evt, StatusSnapshot s) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#StartButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "startListener"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "stopListener"));
        // Connecting / retrying: the grey button cancels (stops) so a stuck retry loop can be ended here.
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#ConnectingButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "stopListener"));
        cmd.set("#PersistCheck.Value", s.persist());
        cmd.set("#PersistCheckLabel.Text", s.persistLabel());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PersistCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "setPersist").append(SettingsEvent.KEY_PERSIST, "#PersistCheck.Value"), false);
        cmd.set("#AutoStartCheck.Value", s.autoStart());
        cmd.set("#AutoStartCheckLabel.Text", s.autoStartLabel());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AutoStartCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "setAutoStart").append(SettingsEvent.KEY_AUTO_START, "#AutoStartCheck.Value"), false);
        cmd.set("#IntervalField.Value", s.tickSeconds());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveIntervalButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveInterval").append(SettingsEvent.KEY_INTERVAL, "#IntervalField.Value"));
    }

    /** Pen tab: creatures dropdown, prefab dropdown, Place, Clear. */
    static void pen(UICommandBuilder cmd, UIEventBuilder evt, StatusSnapshot s, SproutwatchConfig cfg) {
        List<DropdownEntryInfo> creatures = new ArrayList<>();
        for (CreaturePreset p : CreaturePreset.values()) {
            creatures.add(new DropdownEntryInfo(LocalizableString.fromString(p.displayName()), p.id()));
        }
        cmd.set("#CreatureDropdown.Entries", creatures);
        cmd.set("#CreatureDropdown.Value", cfg.getCreatures());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CreatureDropdown",
            new EventData().append(SettingsEvent.KEY_ACTION, "selectCreatures").append(SettingsEvent.KEY_CREATURES, "#CreatureDropdown.Value"), false);

        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (String name : PenPrefabCatalog.names()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(name), name));
        }
        cmd.set("#PrefabDropdown.Entries", entries);
        // Effective name: a stale config value that is no longer in the catalog would otherwise leave
        // the dropdown unselected, so fall back to the catalog default (what PenPlacer will use too).
        String selected = PenPrefabCatalog.contains(s.prefabName()) ? s.prefabName() : PenPrefabCatalog.DEFAULT;
        cmd.set("#PrefabDropdown.Value", selected);
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PrefabDropdown",
            new EventData().append(SettingsEvent.KEY_ACTION, "selectPrefab").append(SettingsEvent.KEY_PREFAB, "#PrefabDropdown.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#PlaceButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "place"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#ClearButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "clear"));
    }
}
