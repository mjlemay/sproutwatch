package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Data the client sends back with a settings-page event. Same shape as the engine's
 * TeleporterSettingsPage.PageEventData: a mutable class filled by a BuilderCodec. Keys without
 * "@" are literals baked into the binding (which button, which row); keys with "@" are read from
 * the named field on the client when the event fires. Every key is optional because each binding
 * carries only the keys it needs, so the fields are boxed and null when absent.
 */
public final class SettingsEvent {

    public static final String KEY_ACTION = "Action";
    public static final String KEY_TAB = "Tab";
    public static final String KEY_VIEWER_KEY = "Login";
    public static final String KEY_FILTER = "Filter";
    public static final String KEY_CHANNEL = "@Channel";
    public static final String KEY_ALLOW_INPUT = "@AllowInput";
    public static final String KEY_IGNORE_INPUT = "@IgnoreInput";
    public static final String KEY_INTERVAL = "@Interval";
    public static final String KEY_MAX = "@Max";
    public static final String KEY_PREFAB = "@Prefab";
    public static final String KEY_CREATURES = "@Creatures";
    public static final String KEY_PERSIST = "@Persist";
    public static final String KEY_AUTO_START = "@AutoStart";
    public static final String KEY_TWITCH_ON = "@TwitchOn";
    public static final String KEY_YOUTUBE_ON = "@YouTubeOn";
    public static final String KEY_YOUTUBE_HANDLE = "@YouTubeHandle";
    /** Read from #YouTubeKeyField only when its Save is pressed; the server never sends the key back. */
    public static final String KEY_YOUTUBE_KEY = "@YouTubeKey";
    public static final String KEY_YOUTUBE_VIDEO = "@YouTubeVideo";

    public static final BuilderCodec<SettingsEvent> CODEC = BuilderCodec.builder(SettingsEvent.class, SettingsEvent::new)
        .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING, false), (e, v) -> e.action = v, e -> e.action).add()
        .append(new KeyedCodec<>(KEY_TAB, Codec.STRING, false), (e, v) -> e.tab = v, e -> e.tab).add()
        .append(new KeyedCodec<>(KEY_VIEWER_KEY, Codec.STRING, false), (e, v) -> e.viewerKey = v, e -> e.viewerKey).add()
        .append(new KeyedCodec<>(KEY_FILTER, Codec.STRING, false), (e, v) -> e.filter = v, e -> e.filter).add()
        .append(new KeyedCodec<>(KEY_CHANNEL, Codec.STRING, false), (e, v) -> e.channel = v, e -> e.channel).add()
        .append(new KeyedCodec<>(KEY_ALLOW_INPUT, Codec.STRING, false), (e, v) -> e.allowInput = v, e -> e.allowInput).add()
        .append(new KeyedCodec<>(KEY_IGNORE_INPUT, Codec.STRING, false), (e, v) -> e.ignoreInput = v, e -> e.ignoreInput).add()
        .append(new KeyedCodec<>(KEY_INTERVAL, Codec.INTEGER, false), (e, v) -> e.interval = v, e -> e.interval).add()
        .append(new KeyedCodec<>(KEY_MAX, Codec.INTEGER, false), (e, v) -> e.max = v, e -> e.max).add()
        .append(new KeyedCodec<>(KEY_PREFAB, Codec.STRING, false), (e, v) -> e.prefab = v, e -> e.prefab).add()
        .append(new KeyedCodec<>(KEY_CREATURES, Codec.STRING, false), (e, v) -> e.creatures = v, e -> e.creatures).add()
        .append(new KeyedCodec<>(KEY_PERSIST, Codec.BOOLEAN, false), (e, v) -> e.persist = v, e -> e.persist).add()
        .append(new KeyedCodec<>(KEY_AUTO_START, Codec.BOOLEAN, false), (e, v) -> e.autoStart = v, e -> e.autoStart).add()
        .append(new KeyedCodec<>(KEY_TWITCH_ON, Codec.BOOLEAN, false), (e, v) -> e.twitchOn = v, e -> e.twitchOn).add()
        .append(new KeyedCodec<>(KEY_YOUTUBE_ON, Codec.BOOLEAN, false), (e, v) -> e.youTubeOn = v, e -> e.youTubeOn).add()
        .append(new KeyedCodec<>(KEY_YOUTUBE_HANDLE, Codec.STRING, false), (e, v) -> e.youTubeHandle = v, e -> e.youTubeHandle).add()
        .append(new KeyedCodec<>(KEY_YOUTUBE_KEY, Codec.STRING, false), (e, v) -> e.youTubeKey = v, e -> e.youTubeKey).add()
        .append(new KeyedCodec<>(KEY_YOUTUBE_VIDEO, Codec.STRING, false), (e, v) -> e.youTubeVideo = v, e -> e.youTubeVideo).add()
        .build();

    public String action;
    public String tab;
    public String viewerKey;
    public String filter;
    public String channel;
    public String allowInput;
    public String ignoreInput;
    public Integer interval;
    public Integer max;
    public String prefab;
    public String creatures;
    public Boolean persist;
    public Boolean autoStart;
    public Boolean twitchOn;
    public Boolean youTubeOn;
    public String youTubeHandle;
    public String youTubeKey;
    public String youTubeVideo;
}
