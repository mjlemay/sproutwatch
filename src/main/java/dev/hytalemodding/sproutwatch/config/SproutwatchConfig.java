package dev.hytalemodding.sproutwatch.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import dev.hytalemodding.sproutwatch.youtube.YouTubeRef;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Codec-backed config for Sproutwatch_config.json. Defaults here must match
 * src/main/resources/Sproutwatch_config.json. Getters clamp; the on-disk value is left as written.
 * BuilderCodec-based, verified working on 0.6.3.
 *
 * AllowUsers / IgnoreUsers entries are Twitch logins or YouTube keys. When hand-editing the JSON,
 * a YouTube entry must be written {@code yt:UC...} (the exact channel ID): a bare {@code UC...} is
 * read as a Twitch login, and {@code yt:@handle} is not resolved from JSON (it is dropped). Handles
 * are resolved only through /sproutwatch allow|ignore add or the Viewers tab, which also record the
 * {@code YouTubeLabels} entry ({@code yt:UC...=@handle}) shown in the lists.
 */
public class SproutwatchConfig {

    /**
     * One entry per kind of sprout, each equally likely. An entry may be a "|" group: one of its roles is
     * picked (the six Sapling colors share one third of the pen). Sprout_* roles ship in the mod's asset
     * pack (Server/NPC/Roles/Sproutwatch, Server/Models/Sproutwatch) and carry random hair and outfits.
     */
    private static final String[] DEFAULT_ROLES = {"Kweebec_Seedling", "Sprout_Sproutling",
        "Sprout_Sapling_Red|Sprout_Sapling_Orange|Sprout_Sapling_Pink|Sprout_Sapling_Yellow|Sprout_Sapling_Green|Sprout_Sapling_Brown"};
    /** The pre-clothing default; a config still holding exactly this is upgraded on load. */
    private static final String[] LEGACY_DEFAULT_ROLES = {"Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"};
    private static final String[] DEFAULT_IGNORE = {"nightbot", "streamelements", "streamlabs"};
    private static final String DEFAULT_QUEUE_COMMAND = "!sprout";
    private static final double DEFAULT_STREAM_HOURS = 8.0;

    private volatile String twitchChannel = "";
    private volatile boolean autoStartOnBoot = false;
    private volatile int tickSeconds = 60;
    private volatile int graceSeconds = 300;
    /** A queued viewer may replace a pen viewer who has not chatted for this long when the pen is full; 0 = never. */
    private volatile int quietSeconds = 600;
    private volatile int maxSprouts = 30;
    private volatile String[] roles = DEFAULT_ROLES.clone();
    private volatile String[] ignoreUsers = DEFAULT_IGNORE.clone();
    private volatile String[] allowUsers = new String[0];
    /** "ignore" (everyone except IgnoreUsers) or "allow" (only AllowUsers; IgnoreUsers still wins). */
    private volatile String viewerFilter = "ignore";
    /** CreaturePreset id: kweebecs (default), pigs, chickens, farm. */
    private volatile String creatures = "kweebecs";
    private volatile String queueCommand = DEFAULT_QUEUE_COMMAND;
    private volatile String penWorld = "";
    private volatile int penX = 0;
    private volatile int penY = 0;
    private volatile int penZ = 0;
    private volatile int penSizeX = 0;
    private volatile int penSizeY = 0;
    private volatile int penSizeZ = 0;
    private volatile boolean chairSet = false;
    private volatile int chairX = 0;
    private volatile int chairY = 0;
    private volatile int chairZ = 0;
    private volatile String penFacing = "north";
    private volatile double cameraHeight = 15.0;
    private volatile double cameraBack = 10.0;
    private volatile double cameraFov = 35.0;
    private volatile boolean cameraFlip = false;
    private volatile boolean persistSprouts = true;
    private volatile String penPrefab = "default";
    private volatile boolean twitchEnabled = true;
    private volatile boolean youTubeEnabled = false;
    private volatile String youTubeHandle = "";
    /** Optional pasted watch link or video ID; overrides finding the live stream from the handle. */
    private volatile String youTubeVideo = "";
    /** Secret: never log it or show it unmasked (StatusSnapshot.maskedKey). */
    private volatile String youTubeApiKey = "";
    private volatile double youTubeStreamHours = DEFAULT_STREAM_HOURS;
    /** Quota day (ISO date, America/Los_Angeles) that YouTubeQuotaUsed belongs to; written by QuotaStore. */
    private volatile String youTubeQuotaDay = "";
    private volatile int youTubeQuotaUsed = 0;
    /** "yt:UC...=@handle" display labels for YouTube allow/ignore entries (the key is what the lists store). */
    private volatile String[] youTubeLabels = new String[0];

    /** Package-private so tests can build defaults; the engine uses CODEC. */
    SproutwatchConfig() {}

    public static final BuilderCodec<SproutwatchConfig> CODEC =
        BuilderCodec.builder(SproutwatchConfig.class, SproutwatchConfig::new)
            .append(new KeyedCodec<>("TwitchChannel", Codec.STRING),
                (c, v, x) -> c.twitchChannel = v, (c, x) -> c.twitchChannel).add()
            .append(new KeyedCodec<>("AutoStartOnBoot", Codec.BOOLEAN),
                (c, v, x) -> c.autoStartOnBoot = v, (c, x) -> c.autoStartOnBoot).add()
            .append(new KeyedCodec<>("TickSeconds", Codec.INTEGER),
                (c, v, x) -> c.tickSeconds = v, (c, x) -> c.tickSeconds).add()
            .append(new KeyedCodec<>("GraceSeconds", Codec.INTEGER),
                (c, v, x) -> c.graceSeconds = v, (c, x) -> c.graceSeconds).add()
            .append(new KeyedCodec<>("QuietSeconds", Codec.INTEGER),
                (c, v, x) -> c.quietSeconds = v, (c, x) -> c.quietSeconds).add()
            .append(new KeyedCodec<>("MaxSprouts", Codec.INTEGER),
                (c, v, x) -> c.maxSprouts = v, (c, x) -> c.maxSprouts).add()
            .append(new KeyedCodec<>("Roles", Codec.STRING_ARRAY),
                (c, v, x) -> c.roles = v, (c, x) -> c.roles).add()
            .append(new KeyedCodec<>("IgnoreUsers", Codec.STRING_ARRAY),
                (c, v, x) -> c.ignoreUsers = v, (c, x) -> c.ignoreUsers).add()
            .append(new KeyedCodec<>("AllowUsers", Codec.STRING_ARRAY),
                (c, v, x) -> c.allowUsers = v, (c, x) -> c.allowUsers).add()
            .append(new KeyedCodec<>("ViewerFilter", Codec.STRING),
                (c, v, x) -> c.viewerFilter = v, (c, x) -> c.viewerFilter).add()
            .append(new KeyedCodec<>("Creatures", Codec.STRING),
                (c, v, x) -> c.creatures = v, (c, x) -> c.creatures).add()
            .append(new KeyedCodec<>("QueueCommand", Codec.STRING),
                (c, v, x) -> c.queueCommand = v, (c, x) -> c.queueCommand).add()
            .append(new KeyedCodec<>("PenWorld", Codec.STRING),
                (c, v, x) -> c.penWorld = v, (c, x) -> c.penWorld).add()
            .append(new KeyedCodec<>("PenX", Codec.INTEGER), (c, v, x) -> c.penX = v, (c, x) -> c.penX).add()
            .append(new KeyedCodec<>("PenY", Codec.INTEGER), (c, v, x) -> c.penY = v, (c, x) -> c.penY).add()
            .append(new KeyedCodec<>("PenZ", Codec.INTEGER), (c, v, x) -> c.penZ = v, (c, x) -> c.penZ).add()
            .append(new KeyedCodec<>("PenSizeX", Codec.INTEGER), (c, v, x) -> c.penSizeX = v, (c, x) -> c.penSizeX).add()
            .append(new KeyedCodec<>("PenSizeY", Codec.INTEGER), (c, v, x) -> c.penSizeY = v, (c, x) -> c.penSizeY).add()
            .append(new KeyedCodec<>("PenSizeZ", Codec.INTEGER), (c, v, x) -> c.penSizeZ = v, (c, x) -> c.penSizeZ).add()
            .append(new KeyedCodec<>("ChairSet", Codec.BOOLEAN), (c, v, x) -> c.chairSet = v, (c, x) -> c.chairSet).add()
            .append(new KeyedCodec<>("ChairX", Codec.INTEGER), (c, v, x) -> c.chairX = v, (c, x) -> c.chairX).add()
            .append(new KeyedCodec<>("ChairY", Codec.INTEGER), (c, v, x) -> c.chairY = v, (c, x) -> c.chairY).add()
            .append(new KeyedCodec<>("ChairZ", Codec.INTEGER), (c, v, x) -> c.chairZ = v, (c, x) -> c.chairZ).add()
            .append(new KeyedCodec<>("PenFacing", Codec.STRING),
                (c, v, x) -> c.penFacing = v, (c, x) -> c.penFacing).add()
            .append(new KeyedCodec<>("CameraHeight", Codec.DOUBLE),
                (c, v, x) -> c.cameraHeight = v, (c, x) -> c.cameraHeight).add()
            .append(new KeyedCodec<>("CameraBack", Codec.DOUBLE),
                (c, v, x) -> c.cameraBack = v, (c, x) -> c.cameraBack).add()
            .append(new KeyedCodec<>("CameraFov", Codec.DOUBLE),
                (c, v, x) -> c.cameraFov = v, (c, x) -> c.cameraFov).add()
            .append(new KeyedCodec<>("CameraFlip", Codec.BOOLEAN),
                (c, v, x) -> c.cameraFlip = v, (c, x) -> c.cameraFlip).add()
            .append(new KeyedCodec<>("PersistSprouts", Codec.BOOLEAN),
                (c, v, x) -> c.persistSprouts = v, (c, x) -> c.persistSprouts).add()
            .append(new KeyedCodec<>("PenPrefab", Codec.STRING),
                (c, v, x) -> c.penPrefab = v, (c, x) -> c.penPrefab).add()
            .append(new KeyedCodec<>("TwitchEnabled", Codec.BOOLEAN),
                (c, v, x) -> c.twitchEnabled = v, (c, x) -> c.twitchEnabled).add()
            .append(new KeyedCodec<>("YouTubeEnabled", Codec.BOOLEAN),
                (c, v, x) -> c.youTubeEnabled = v, (c, x) -> c.youTubeEnabled).add()
            .append(new KeyedCodec<>("YouTubeHandle", Codec.STRING),
                (c, v, x) -> c.youTubeHandle = v, (c, x) -> c.youTubeHandle).add()
            .append(new KeyedCodec<>("YouTubeVideo", Codec.STRING),
                (c, v, x) -> c.youTubeVideo = v, (c, x) -> c.youTubeVideo).add()
            .append(new KeyedCodec<>("YouTubeApiKey", Codec.STRING),
                (c, v, x) -> c.youTubeApiKey = v, (c, x) -> c.youTubeApiKey).add()
            .append(new KeyedCodec<>("YouTubeStreamHours", Codec.DOUBLE),
                (c, v, x) -> c.youTubeStreamHours = v, (c, x) -> c.youTubeStreamHours).add()
            .append(new KeyedCodec<>("YouTubeQuotaDay", Codec.STRING),
                (c, v, x) -> c.youTubeQuotaDay = v, (c, x) -> c.youTubeQuotaDay).add()
            .append(new KeyedCodec<>("YouTubeQuotaUsed", Codec.INTEGER),
                (c, v, x) -> c.youTubeQuotaUsed = v, (c, x) -> c.youTubeQuotaUsed).add()
            .append(new KeyedCodec<>("YouTubeLabels", Codec.STRING_ARRAY),
                (c, v, x) -> c.youTubeLabels = v, (c, x) -> c.youTubeLabels).add()
            .build();

    /** IRC-safe channel form: lowercase, [a-z0-9_] only. */
    public static String normalizeChannel(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    public String getTwitchChannel() { return normalizeChannel(twitchChannel); }
    public void setTwitchChannel(String v) { twitchChannel = v; }
    public boolean isAutoStartOnBoot() { return autoStartOnBoot; }
    public void setAutoStartOnBoot(boolean v) { autoStartOnBoot = v; }
    public int getTickSeconds() { return Math.max(5, tickSeconds); }
    public void setTickSeconds(int v) { tickSeconds = v; }
    public int getGraceSeconds() { return Math.max(0, graceSeconds); }
    public void setGraceSeconds(int v) { graceSeconds = v; }
    public int getQuietSeconds() { return Math.max(0, quietSeconds); }
    public void setQuietSeconds(int v) { quietSeconds = v; }
    public int getMaxSprouts() { return Math.max(1, maxSprouts); }
    public void setMaxSprouts(int v) { maxSprouts = v; }

    /**
     * Cleaned copy: trimmed, non-blank, de-duplicated (order preserved), never empty
     * (falls back to the defaults when nothing is left). Callers may pass this to Set.of(...).
     */
    public String[] getRoles() {
        String[] r = roles;
        Set<String> cleaned = new LinkedHashSet<>();
        if (r != null) {
            for (String s : r) {
                if (s == null) continue;
                String trimmedEntry = s.trim();
                if (!trimmedEntry.isEmpty()) cleaned.add(trimmedEntry);
            }
        }
        return cleaned.isEmpty() ? DEFAULT_ROLES.clone() : cleaned.toArray(new String[0]);
    }
    public void setRoles(String[] v) { roles = v; }

    /** Every individual role, groups split: what the pen sweep (Clear / Place) looks for. */
    public Set<String> allRoles() {
        return splitRoles(getRoles());
    }

    private static Set<String> splitRoles(String[] entries) {
        Set<String> all = new LinkedHashSet<>();
        for (String entry : entries) {
            for (String part : entry.split("\\|")) {
                String trimmedPart = part.trim();
                if (!trimmedPart.isEmpty()) all.add(trimmedPart);
            }
        }
        return all;
    }

    /**
     * What the pen sweep (boot, Clear, re-Place) removes: every current role plus the roles this mod
     * spawned before the clothing upgrade, so sprouts left in the pen by an older run are still found.
     */
    public Set<String> sweepRoles() {
        Set<String> all = allRoles();
        all.addAll(java.util.Arrays.asList(LEGACY_DEFAULT_ROLES));
        // Every preset's roles too, so animals left in the pen from another creature set are found.
        for (CreaturePreset p : CreaturePreset.values()) all.addAll(splitRoles(p.roles(getRoles())));
        return all;
    }

    public String getCreatures() { return CreaturePreset.parse(creatures).id(); }
    public void setCreatures(String id) { creatures = CreaturePreset.parse(id).id(); }

    /** What the spawner picks from: the selected creature preset (Kweebecs = the Roles list). */
    public String[] spawnRoles() {
        return CreaturePreset.parse(creatures).roles(getRoles());
    }

    /** @return true if Roles still held the pre-clothing default and was replaced by the current one. */
    public boolean upgradeLegacyRoles() {
        if (!java.util.Arrays.equals(getRoles(), LEGACY_DEFAULT_ROLES)) return false;
        roles = DEFAULT_ROLES.clone();
        return true;
    }

    /** Package-private test hooks; the engine only ever sets these via CODEC (or the mutators below). */
    void setIgnoreUsersForTest(String[] v) { ignoreUsers = v; }
    void setAllowUsersForTest(String[] v) { allowUsers = v; }
    void setViewerFilterForTest(String v) { viewerFilter = v; }
    void setQueueCommandForTest(String v) { queueCommand = v; }
    void setYouTubeLabelsForTest(String[] v) { youTubeLabels = v; }

    /**
     * One list entry in its stored form: a Twitch login normalized IRC-safe (lowercase), or a YouTube
     * key {@code yt:<channelId>} kept exact (channel IDs are case-sensitive). "" when unusable (an
     * invalid {@code yt:} entry is dropped rather than mangled into a Twitch login).
     */
    public static String normalizeEntry(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, ViewerKey.YOUTUBE_PREFIX, 0, ViewerKey.YOUTUBE_PREFIX.length())) {
            return YouTubeRef.parseChannelId(trimmed.substring(ViewerKey.YOUTUBE_PREFIX.length()))
                .map(id -> ViewerKey.YOUTUBE_PREFIX + id).orElse("");
        }
        return normalizeChannel(trimmed);
    }

    /** Normalized ignore list (Twitch logins lowercased, yt: keys exact) plus the channel login itself (the streamer never gets a sprout). */
    public Set<String> ignoredViewers() {
        Set<String> out = normalizedViewers(ignoreUsers);
        String ch = getTwitchChannel();
        if (!ch.isEmpty()) out.add(ch);
        return out;
    }

    /** Normalized allow list (Twitch logins lowercased, yt: keys exact); applied only in allow mode (see {@link #passesFilter}). */
    public Set<String> allowedViewers() {
        return normalizedViewers(allowUsers);
    }

    /** "allow" or "ignore"; anything else reads as "ignore". */
    public String getViewerFilter() {
        return "allow".equalsIgnoreCase(viewerFilter == null ? "" : viewerFilter.trim()) ? "allow" : "ignore";
    }

    public boolean isAllowMode() { return getViewerFilter().equals("allow"); }

    public void setAllowMode(boolean allow) { viewerFilter = allow ? "allow" : "ignore"; }

    /**
     * The viewer filter: in ignore mode everyone not on IgnoreUsers is eligible; in allow mode only
     * AllowUsers are (IgnoreUsers still wins; an empty allow list means nobody). The viewer key must be normalized.
     */
    public boolean passesFilter(String viewerKey) {
        if (ignoredViewers().contains(viewerKey)) return false;
        return !isAllowMode() || allowedViewers().contains(viewerKey);
    }

    /** Chat text that queues a viewer for the next sprout; trimmed, default "!sprout" when blank. */
    public String getQueueCommand() {
        String q = queueCommand == null ? "" : queueCommand.trim();
        return q.isEmpty() ? DEFAULT_QUEUE_COMMAND : q;
    }

    /** @return true if the (normalized) viewer key was added to AllowUsers; false if blank or already there. */
    public synchronized boolean addAllow(String raw) {
        String[] next = withViewer(allowUsers, raw);
        if (next == null) return false;
        allowUsers = next;
        return true;
    }

    /** @return true if the (normalized) viewer key was removed from AllowUsers. */
    public synchronized boolean removeAllow(String raw) {
        String[] next = withoutViewer(allowUsers, raw);
        if (next == null) return false;
        allowUsers = next;
        dropOrphanLabel(raw);
        return true;
    }

    /** @return true if the (normalized) viewer key was added to IgnoreUsers; false if blank or already there. */
    public synchronized boolean addIgnore(String raw) {
        String[] next = withViewer(ignoreUsers, raw);
        if (next == null) return false;
        ignoreUsers = next;
        return true;
    }

    /** @return true if the (normalized) viewer key was removed from IgnoreUsers. */
    public synchronized boolean removeIgnore(String raw) {
        String[] next = withoutViewer(ignoreUsers, raw);
        if (next == null) return false;
        ignoreUsers = next;
        dropOrphanLabel(raw);
        return true;
    }

    // ---- YouTube entry labels ----------------------------------------------------------------

    /** yt: key -> label ("@handle"), insertion-ordered; malformed or non-yt entries are skipped. */
    public Map<String, String> youTubeLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        String[] raw = youTubeLabels;
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = normalizeEntry(s.substring(0, eq));
            String label = s.substring(eq + 1).trim();
            if (key.startsWith(ViewerKey.YOUTUBE_PREFIX) && !label.isEmpty()) out.put(key, label);
        }
        return out;
    }

    public Optional<String> youTubeLabel(String key) {
        return Optional.ofNullable(youTubeLabels().get(normalizeEntry(key)));
    }

    /** Stores (or with a blank label, drops) the label of a yt: key; non-YouTube keys are ignored. */
    public synchronized void setYouTubeLabel(String key, String label) {
        String k = normalizeEntry(key);
        if (!k.startsWith(ViewerKey.YOUTUBE_PREFIX)) return;
        Map<String, String> m = youTubeLabels();
        String trimmedLabel = label == null ? "" : label.trim();
        if (trimmedLabel.isEmpty()) m.remove(k); else m.put(k, trimmedLabel);
        writeLabels(m);
    }

    /**
     * Adds a yt: key to one list and records its label in one step, so a concurrent remove can never
     * leave a label without its entry. The label is (re)written even when the key was already listed.
     * @return true if the key was newly added
     */
    public synchronized boolean addYouTubeEntry(boolean allowList, String key, String label) {
        String k = normalizeEntry(key);
        if (!k.startsWith(ViewerKey.YOUTUBE_PREFIX)) return false;
        boolean added = allowList ? addAllow(k) : addIgnore(k);
        if (label != null && !label.isBlank()) setYouTubeLabel(k, label);
        return added;
    }

    /** The yt: key whose stored label equals this handle, ignoring case. */
    public Optional<String> youTubeKeyForLabel(String handle) {
        if (handle == null || handle.isBlank()) return Optional.empty();
        String trimmedHandle = handle.trim();
        for (Map.Entry<String, String> e : youTubeLabels().entrySet()) {
            if (e.getValue().equalsIgnoreCase(trimmedHandle)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** How a list entry reads to the streamer: "@handle (YouTube)", "UC... (YouTube)", or the Twitch login. */
    public String entryDisplay(String key) {
        String k = normalizeEntry(key);
        if (!k.startsWith(ViewerKey.YOUTUBE_PREFIX)) return k;
        return youTubeLabel(k).orElse(k.substring(ViewerKey.YOUTUBE_PREFIX.length())) + " (YouTube)";
    }

    /** A label outlives its entry only while the key is still on the other list. */
    private void dropOrphanLabel(String raw) {
        String k = normalizeEntry(raw);
        if (!k.startsWith(ViewerKey.YOUTUBE_PREFIX)) return;
        if (normalizedViewers(allowUsers).contains(k) || normalizedViewers(ignoreUsers).contains(k)) return;
        Map<String, String> m = youTubeLabels();
        if (m.remove(k) != null) writeLabels(m);
    }

    private void writeLabels(Map<String, String> m) {
        youTubeLabels = m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /** Normalized ({@link #normalizeEntry}), non-blank, de-duplicated (order preserved) copy of a viewer key array. */
    private static Set<String> normalizedViewers(String[] raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                if (s == null) continue;
                String entry = normalizeEntry(s);
                if (!entry.isEmpty()) out.add(entry);
            }
        }
        return out;
    }

    /** New array with the viewer key appended, or null when blank or already present. */
    private static String[] withViewer(String[] current, String raw) {
        String viewerKey = normalizeEntry(raw);
        if (viewerKey.isEmpty()) return null;
        Set<String> set = normalizedViewers(current);
        if (!set.add(viewerKey)) return null;
        return set.toArray(new String[0]);
    }

    /** New array with the viewer key dropped, or null when blank or not present. */
    private static String[] withoutViewer(String[] current, String raw) {
        String viewerKey = normalizeEntry(raw);
        if (viewerKey.isEmpty()) return null;
        Set<String> set = normalizedViewers(current);
        if (!set.remove(viewerKey)) return null;
        return set.toArray(new String[0]);
    }

    public String getPenWorld() { return penWorld == null ? "" : penWorld; }
    public boolean isPenSet() { return !getPenWorld().isEmpty() && penSizeX > 0 && penSizeZ > 0; }
    public int getPenX() { return penX; }
    public int getPenY() { return penY; }
    public int getPenZ() { return penZ; }
    public int getPenSizeX() { return penSizeX; }
    public int getPenSizeY() { return penSizeY; }
    public int getPenSizeZ() { return penSizeZ; }

    /** x/y/z = world min corner of the interior (y = floor block); sizes = interior x/z and clear height. */
    public void setPen(String worldUuid, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        penWorld = worldUuid;
        penX = x; penY = y; penZ = z;
        penSizeX = sizeX; penSizeY = sizeY; penSizeZ = sizeZ;
    }

    public boolean isChairSet() { return chairSet; }
    public int getChairX() { return chairX; }
    public int getChairY() { return chairY; }
    public int getChairZ() { return chairZ; }
    public void setChair(boolean set, int x, int y, int z) {
        chairSet = set;
        chairX = x; chairY = y; chairZ = z;
    }

    /**
     * Forgets the pen (Remove pen): the pen world, interior and sizes, the chair and the facing go
     * back to their unset defaults, so {@link #isPenSet()} is false.
     */
    public void clearPen() {
        setPen("", 0, 0, 0, 0, 0, 0);
        setChair(false, 0, 0, 0);
        penFacing = "north";
    }

    public String getPenFacing() { return PenFacing.parse(penFacing).key(); }
    public void setPenFacing(String v) { penFacing = v; }

    public double getCameraHeight() { return Double.isFinite(cameraHeight) ? cameraHeight : 15.0; }
    public double getCameraBack() { return Double.isFinite(cameraBack) ? cameraBack : 10.0; }
    public double getCameraFov() {
        return Double.isFinite(cameraFov) ? Math.max(10.0, Math.min(170.0, cameraFov)) : 35.0;
    }
    public void setCamera(double height, double back, double fov) {
        cameraHeight = height; cameraBack = back; cameraFov = fov;
    }
    public boolean isCameraFlip() { return cameraFlip; }
    public void setCameraFlip(boolean v) { cameraFlip = v; }

    /**
     * Persist: sprouts stay after their viewer leaves chat (GraceSeconds ignored); at MaxSprouts the
     * sprout whose viewer has been gone the longest is replaced by a waiting newcomer, one per tick.
     */
    public boolean isPersistSprouts() { return persistSprouts; }
    public void setPersistSprouts(boolean v) { persistSprouts = v; }

    /** Bundled pen prefab name (see PenPrefabCatalog): trimmed lowercase, "default" when blank. */
    public String getPenPrefab() {
        String prefabName = penPrefab == null ? "" : penPrefab.trim().toLowerCase(Locale.ROOT);
        return prefabName.isEmpty() ? "default" : prefabName;
    }
    public void setPenPrefab(String v) { penPrefab = v; }

    // ---- chat sources ------------------------------------------------------------------------

    public boolean isTwitchEnabled() { return twitchEnabled; }
    public void setTwitchEnabled(boolean v) { twitchEnabled = v; }
    public boolean isYouTubeEnabled() { return youTubeEnabled; }
    public void setYouTubeEnabled(boolean v) { youTubeEnabled = v; }
    public String getYouTubeHandle() { return trim(youTubeHandle); }
    public void setYouTubeHandle(String v) { youTubeHandle = v; }
    public String getYouTubeVideo() { return trim(youTubeVideo); }
    public void setYouTubeVideo(String v) { youTubeVideo = v; }
    /** The raw API key (secret): pass it to YouTubeApi only, never into a message or log line. */
    public String getYouTubeApiKey() { return trim(youTubeApiKey); }
    public void setYouTubeApiKey(String v) { youTubeApiKey = v; }

    /** Planned stream length the quota pacer spreads the daily budget over: 1..24 h, 8 when unreadable. */
    public double getYouTubeStreamHours() {
        double hours = youTubeStreamHours;
        return Double.isFinite(hours) ? Math.clamp(hours, 1.0, 24.0) : DEFAULT_STREAM_HOURS;
    }
    public void setYouTubeStreamHours(double v) { youTubeStreamHours = v; }

    public String getYouTubeQuotaDay() { return trim(youTubeQuotaDay); }
    public int getYouTubeQuotaUsed() { return Math.max(0, youTubeQuotaUsed); }
    /** Persisted quota usage (QuotaPacer.quotaDay / usedToday), restored on the next YouTube start. */
    public void setYouTubeQuota(String day, int used) {
        youTubeQuotaDay = day;
        youTubeQuotaUsed = used;
    }

    /** YouTube is on and has everything a start needs: an API key and a usable handle or stream link. */
    public boolean youTubeConfigured() {
        return isYouTubeEnabled() && !getYouTubeApiKey().isEmpty() && hasYouTubeTarget();
    }

    /** A handle or a pasted stream link that parses. */
    public boolean hasYouTubeTarget() {
        return YouTubeRef.parseHandle(getYouTubeHandle()).isPresent()
            || YouTubeRef.parseVideoId(getYouTubeVideo()).isPresent();
    }

    /** Twitch can start: enabled and a channel is set. */
    public boolean twitchReady() {
        return isTwitchEnabled() && !getTwitchChannel().isEmpty();
    }

    /** What a YouTube start still needs: "an API key", "your @handle or a stream link", both, or null. */
    public String youTubeMissing() {
        boolean noKey = getYouTubeApiKey().isEmpty();
        boolean noTarget = !hasYouTubeTarget();
        if (noKey && noTarget) return "an API key and your @handle or a stream link";
        if (noKey) return "an API key";
        if (noTarget) return "your @handle or a stream link";
        return null;
    }

    /**
     * Why Start cannot start any chat source, or null when at least one can (Twitch with a channel,
     * or a configured YouTube). The plugin returns this as its start error.
     */
    public String nothingToStartReason() {
        if (twitchReady() || youTubeConfigured()) return null;
        String missing = youTubeMissing();
        if (isTwitchEnabled()) {
            if (!isYouTubeEnabled()) return "No Twitch channel set. Use /sproutwatch channel <name> first.";
            return "Set a Twitch channel or finish YouTube setup (needs " + missing + ") first.";
        }
        if (isYouTubeEnabled()) return "Twitch is off and YouTube is on but needs " + missing + ".";
        return "Twitch is off and YouTube is off. Turn Twitch on, or turn YouTube on"
            + (missing == null ? "." : " and give it " + missing + ".");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
