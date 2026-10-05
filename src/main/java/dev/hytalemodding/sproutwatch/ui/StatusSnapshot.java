package dev.hytalemodding.sproutwatch.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time view of everything the settings page and /sproutwatch status show, plus the
 * wording of every line, so the text is unit-tested once and shared by page and command.
 * penWorldName is null when the pen world is not loaded; penWorldId is the configured UUID string.
 * sourceStates is "Twitch" / "YouTube" → state (insertion-ordered, see {@link #sourceStates(String, boolean,
 * String, boolean)}); youTube never carries the raw API key.
 */
public record StatusSnapshot(
    String listenerState, boolean listenerRunning, boolean feedAcknowledged,
    String channel, int rosterSize, int queueSize, String queueCommand, boolean allowMode, int allowCount, int ignoreCount,
    int penCount, int cap, int retiredCount, int tickSeconds, int graceSeconds, int quietSeconds, boolean tickerRunning,
    boolean persist, boolean autoStart,
    boolean penSet, int penX, int penY, int penZ, int penSizeX, int penSizeZ, String penFacing,
    String penWorldName, String penWorldId,
    boolean chairSet, int chairX, int chairY, int chairZ,
    String prefabName,
    Map<String, String> sourceStates, YouTubeStatus youTube) {

    public static final String TWITCH = "Twitch";
    public static final String YOUTUBE = "YouTube";

    public StatusSnapshot {
        sourceStates = sourceStates == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceStates));
        youTube = youTube == null ? YouTubeStatus.OFF : youTube;
    }

    /** Twitch-only form (no per-source states, YouTube off), kept for callers that predate YouTube. */
    public StatusSnapshot(
        String listenerState, boolean listenerRunning, boolean feedAcknowledged,
        String channel, int rosterSize, int queueSize, String queueCommand, boolean allowMode, int allowCount, int ignoreCount,
        int penCount, int cap, int retiredCount, int tickSeconds, int graceSeconds, int quietSeconds, boolean tickerRunning,
        boolean persist, boolean autoStart,
        boolean penSet, int penX, int penY, int penZ, int penSizeX, int penSizeZ, String penFacing,
        String penWorldName, String penWorldId,
        boolean chairSet, int chairX, int chairY, int chairZ,
        String prefabName) {
        this(listenerState, listenerRunning, feedAcknowledged, channel, rosterSize, queueSize, queueCommand, allowMode, allowCount,
            ignoreCount, penCount, cap, retiredCount, tickSeconds, graceSeconds, quietSeconds, tickerRunning, persist,
            autoStart, penSet, penX, penY, penZ, penSizeX, penSizeZ, penFacing, penWorldName, penWorldId, chairSet,
            chairX, chairY, chairZ, prefabName, Map.of(), YouTubeStatus.OFF);
    }

    public String listenerLine() {
        return "Listener: " + (sourceStates.isEmpty() ? listenerState : joinStates(sourceStates));
    }

    // ---- YouTube lines (status command, only while YouTube is enabled) -----------------------

    public String youTubeLine() {
        String state = sourceStates.get(YOUTUBE);
        if (state == null) state = youTube.missing() != null ? "needs " + youTube.missing() : "stopped";
        return "YouTube: " + state;
    }

    public String youTubeChannelLine() {
        return "YouTube channel: " + (youTube.target().isEmpty() ? "(unset)" : youTube.target());
    }

    public String youTubeKeyLine() {
        return "YouTube key: " + youTube.maskedKey();
    }

    public String youTubeQuotaLine() {
        return "YouTube quota: " + quotaText();
    }

    /**
     * Details-table value of the "YouTube quota" row: "1,234 / 9,800 used today (resets 00:00)" while
     * YouTube is enabled, else "YouTube off".
     */
    public String youTubeQuotaValue() {
        return youTube.enabled() ? quotaText() : "YouTube off";
    }

    /** "1,234 / 9,800 used today (resets 00:00)": shared by the Details row and the status line. */
    private String quotaText() {
        return String.format(java.util.Locale.US, "%,d / %,d used today (resets %s)",
            youTube.quotaUsed(), youTube.quotaBudget(), youTube.resetsAt());
    }

    /**
     * The API key as it may be shown: first 4 + "..." + last 4 for keys of 12+ characters, else "set";
     * "not set" when blank. Never the whole key.
     */
    public static String maskedKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return "not set";
        if (k.length() < 12) return "set";
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }

    /**
     * Per-source states for the status: a started source shows its own state (even one that ended on
     * its own, e.g. "chat ended"); one not started shows "stopped" when it is enabled and configured,
     * and is left out otherwise.
     * @param twitchState the started Twitch source's state, or null when none is started
     * @param youTubeState     the started YouTube source's state, or null when none is started
     */
    public static Map<String, String> sourceStates(String twitchState, boolean twitchConfigured,
                                                   String youTubeState, boolean youTubeConfigured) {
        Map<String, String> m = new LinkedHashMap<>();
        if (twitchState != null) m.put(TWITCH, twitchState);
        else if (twitchConfigured) m.put(TWITCH, "stopped");
        if (youTubeState != null) m.put(YOUTUBE, youTubeState);
        else if (youTubeConfigured) m.put(YOUTUBE, "stopped");
        return m;
    }

    /** "stopped" when empty or all stopped; one source's state alone; else "Twitch: a · YouTube: b". */
    public static String joinStates(Map<String, String> states) {
        return joinStates(states, " · ");
    }

    /** As {@link #joinStates(Map)}, with {@code separator} between the sources (the settings page uses a line break). */
    public static String joinStates(Map<String, String> states, String separator) {
        if (states.values().stream().allMatch("stopped"::equals)) return "stopped";
        if (states.size() == 1) return states.values().iterator().next();
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : states.entrySet()) {
            if (!joined.isEmpty()) joined.append(separator);
            joined.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return joined.toString();
    }

    /** The listener state for the settings page: with both sources, each on its own line. */
    private String listenerStateOnPage() {
        return sourceStates.isEmpty() ? listenerState : joinStates(sourceStates, "\n");
    }

    public String channelLine() {
        return "Channel: " + (channel.isEmpty() ? "(unset)" : "#" + channel);
    }

    /** The status command prints this only while the listener runs; the page always shows it. */
    public String feedLine() {
        if (!listenerRunning) return "Twitch JOIN/PART feed: n/a (listener stopped)";
        return "Twitch JOIN/PART feed: " + (feedAcknowledged
            ? "on"
            : "OFF (Twitch did not grant membership; only viewers who chat will appear)");
    }

    public String rosterLine() {
        // Twitch delivers JOIN/PART/NAMES only for channels under ~1,000 chatters; above that the roster
        // is just the people who typed, so say so rather than look like a viewer count.
        return "Seen in chat: " + rosterSize + " (chatters since Start)";
    }

    public String queueLine() {
        return "Queue: " + queueSize + " waiting (typed " + queueCommand + " in chat)";
    }

    public String filterLine() {
        return "Filter: " + filterValue();
    }

    private String filterValue() {
        return allowMode
            ? "allow list (" + allowCount + " allowed)"
            : "ignore list (everyone in chat except " + ignoreCount + " ignored)";
    }

    /** Caption under the Viewers tab's filter toggle. */
    public String filterLabel() {
        if (allowMode) {
            return allowCount == 0
                ? "Only viewers on the allow list get a sprout. The list is empty: nobody until you add someone."
                : "Only the " + allowCount + " viewer(s) on the allow list get a sprout.";
        }
        return "Everyone in chat gets a sprout except the " + ignoreCount + " on the ignore list (and the channel).";
    }

    public String penCountLine() {
        return "Pen: " + penCount + "/" + cap + " sprouts, tick " + tickSeconds + "s, grace " + graceSeconds
            + "s, ticker " + (tickerRunning ? "running" : "stopped");
    }

    public String persistLine() {
        return "Persist: " + (persist ? "on (longest-gone replaced at the cap)" : "off (grace " + graceSeconds + "s)");
    }

    public String retiredLine() {
        return "Retired (killed by a player): " + retiredCount;
    }

    public String penPlacedLine() {
        if (!penSet) return "Pen: not placed (run /sproutwatch place)";
        return "Pen placed: " + penSizeX + "x" + penSizeZ + " at " + penX + "," + penY + "," + penZ
            + " in " + (penWorldName == null ? "an unloaded world (" + penWorldId + ")" : penWorldName)
            + ", camera faces " + penFacing;
    }

    public String chairLine() {
        if (!penSet) return "Chair: (no pen)";
        return "Chair: " + (chairSet ? chairX + "," + chairY + "," + chairZ : "(none; use /sproutwatch camera)");
    }

    /** Caption beside the Listener tab's Start / Stop button. */
    public String runLabel() {
        return listenerRunning
            ? "Listener running (" + listenerStateOnPage() + ")"
            : "Listener stopped";
    }

    /** Caption of the Listener tab persist checkbox. */
    public String persistLabel() {
        return persist
            ? "Persist on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced"
            : "Persist off: sprouts despawn " + graceSeconds + "s after their viewer leaves";
    }

    /** Text above the Connect tab's Wrangle Viewers button (the button is disabled without a pen). */
    public String beginCaption() {
        return penSet ? "Close and bring viewers to the pen." : "Place a pen to wrangle viewers";
    }

    /** Caption of the Listener tab auto-start checkbox. */
    public String autoStartLabel() {
        return autoStart
            ? "Auto-start on: the listener reconnects when the world loads"
            : "Auto-start off: press Start listener after each launch";
    }

    /** Static row names of the Details table, in {@link #detailValues()} order (the .ui prints these). */
    public static final List<String> DETAIL_NAMES = List.of(
        "Channel", "Listeners", "Twitch feed", "YouTube quota", "Persist", "Seen in chat", "Queue", "Filter", "Pen", "Retired");

    /** The value-cell ids in SettingsPage.ui, one per DETAIL_NAMES entry, same order. */
    public static final List<String> DETAIL_IDS = List.of(
        "#ChannelValue", "#ListenerValue", "#FeedValue", "#YouTubeQuotaValue", "#PersistValue", "#RosterValue", "#QueueValue", "#FilterValue",
        "#PenCountValue", "#RetiredValue");

    /** The Details table's value cells: each status line without its "Name: " prefix, in DETAIL_NAMES order. */
    public List<String> detailValues() {
        return List.of(
            value(channelLine()), listenerStateOnPage(), value(feedLine()), youTubeQuotaValue(), value(persistLine()), value(rosterLine()),
            value(queueLine()), filterValue(), value(penCountLine()), value(retiredLine()));
    }

    /** Name of the LabelStyle in SettingsPage.ui for the Listener cell: green connected, yellow connecting, red lost. */
    public String listenerTone() {
        if (sourceStates.isEmpty()) return toneFor(listenerState);
        String worst = "ValueGood";
        for (String state : sourceStates.values()) {
            String tone = toneFor(state);
            if (TONE_RANK.indexOf(tone) > TONE_RANK.indexOf(worst)) worst = tone;
        }
        return worst;
    }

    /** Tones from best to worst; the Listener cell takes the worst across sources. */
    private static final List<String> TONE_RANK = List.of("ValueGood", "ValuePlain", "ValueWarn", "ValueBad");

    /**
     * Which Listener-tab button shows, for any number of sources: START (nothing runs),
     * CONNECTING (a source is still connecting / retrying), else STOP. A source that ended on its
     * own (chat ended, not live, key rejected) or waits for the quota reset counts as settled.
     */
    public RunButton runButton() {
        return runButtonFor(listenerRunning, sourceStates.isEmpty() ? java.util.Collections.singletonList(listenerState) : sourceStates.values());
    }

    public static RunButton runButtonFor(boolean running, String state) {
        return runButtonFor(running, java.util.Collections.singletonList(state));
    }

    public static RunButton runButtonFor(boolean running, java.util.Collection<String> states) {
        if (!running) return RunButton.START;
        for (String state : states) {
            if (state != null && (state.startsWith("connecting") || state.startsWith("reconnecting"))) return RunButton.CONNECTING;
        }
        return RunButton.STOP;
    }

    public static String toneFor(String state) {
        String normalized = state == null ? "" : state;
        if (normalized.startsWith("connected")) return "ValueGood";
        if (normalized.startsWith("connecting")) return "ValueWarn";
        if (normalized.startsWith("reconnecting")) return "ValueBad";
        return "ValuePlain";
    }

    private static String value(String line) {
        int i = line.indexOf(": ");
        return i < 0 ? line : line.substring(i + 2);
    }


    /**
     * The /sproutwatch status text: the pre-page lines (the Twitch feed line only while Twitch runs),
     * plus the four YouTube lines (state, channel, masked key, quota) while YouTube is enabled.
     */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(listenerLine()).append('\n');
        sb.append(channelLine()).append('\n');
        if (listenerRunning && (sourceStates.isEmpty() || sourceStates.containsKey(TWITCH))) sb.append(feedLine()).append('\n');
        if (youTube.enabled()) {
            sb.append(youTubeLine()).append('\n');
            sb.append(youTubeChannelLine()).append('\n');
            sb.append(youTubeKeyLine()).append('\n');
            sb.append(youTubeQuotaLine()).append('\n');
        }
        sb.append(rosterLine()).append('\n');
        sb.append(queueLine()).append('\n');
        sb.append(filterLine()).append('\n');
        sb.append(penCountLine()).append('\n');
        sb.append(persistLine()).append('\n');
        if (retiredCount > 0) sb.append(retiredLine()).append('\n');
        sb.append(penPlacedLine());
        if (penSet) sb.append('\n').append(chairLine());
        return sb.toString();
    }
}
