package dev.hytalemodding.sproutwatch.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every action a settings page button can send, with its wire name: the string bound into the
 * button's EventData (SettingsPanes, SettingsTab) and sent back verbatim by the client in
 * {@link SettingsEvent#action}. The page's dispatcher switches over this enum without a default,
 * so a new constant without a case fails to compile. Wire names must never change: they are what
 * the client sends.
 */
enum PageAction {
    TAB("tab"),
    BEGIN("begin"),
    SAVE_CHANNEL("saveChannel"),
    REMOVE_CHANNEL("removeChannel"),
    SET_TWITCH_ENABLED("setTwitchEnabled"),
    SET_YOUTUBE_ENABLED("setYouTubeEnabled"),
    SAVE_YOUTUBE_HANDLE("saveYouTubeHandle"),
    REMOVE_YOUTUBE_HANDLE("removeYouTubeHandle"),
    SAVE_YOUTUBE_KEY("saveYouTubeKey"),
    REMOVE_YOUTUBE_KEY("removeYouTubeKey"),
    SAVE_YOUTUBE_VIDEO("saveYouTubeVideo"),
    REMOVE_YOUTUBE_VIDEO("removeYouTubeVideo"),
    SAVE_MAX("saveMax"),
    SET_FILTER("setFilter"),
    ADD_ALLOW("addAllow"),
    REMOVE_ALLOW("removeAllow"),
    ADD_IGNORE("addIgnore"),
    REMOVE_IGNORE("removeIgnore"),
    START_LISTENER("startListener"),
    STOP_LISTENER("stopListener"),
    SET_PERSIST("setPersist"),
    SET_AUTO_START("setAutoStart"),
    SAVE_INTERVAL("saveInterval"),
    SELECT_CREATURES("selectCreatures"),
    SELECT_PREFAB("selectPrefab"),
    PLACE("place"),
    CLEAR("clear");

    private static final Map<String, PageAction> BY_WIRE_NAME = byWireName();

    private static Map<String, PageAction> byWireName() {
        Map<String, PageAction> actions = new HashMap<>();
        for (PageAction action : values()) actions.put(action.wireName, action);
        return Map.copyOf(actions);
    }

    private final String wireName;

    PageAction(String wireName) {
        this.wireName = wireName;
    }

    /** The string bound into the button's EventData and sent back by the client. */
    String wireName() {
        return wireName;
    }

    /** The action the client named, or empty when the name is missing or unknown (case-sensitive). */
    static Optional<PageAction> fromWire(String wireName) {
        if (wireName == null) return Optional.empty();
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }
}
