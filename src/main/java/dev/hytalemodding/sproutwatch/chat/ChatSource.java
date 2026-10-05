package dev.hytalemodding.sproutwatch.chat;

/**
 * One live chat (a Twitch channel, a YouTube live stream) feeding the shared
 * {@link dev.hytalemodding.sproutwatch.twitch.ChatRoster}. Each source owns its own background
 * thread; the plugin starts, stops and reports on all enabled sources together.
 *
 * {@link #getState()} is a short human-readable string shown on the settings page and in
 * {@code /sproutwatch status}. The page colors it by prefix: {@code connected…} is good,
 * {@code connecting…} is a warning (still trying), {@code reconnecting…} is bad.
 *
 * Life cycle: a source may be one-shot ({@code TwitchMembershipClient} throws on a second
 * {@link #start()}) or restartable ({@code YouTubeChatSource} begins a fresh run). Callers must not
 * rely on either: the plugin constructs a fresh source per Start and never reuses one after
 * {@link #stop()}. A source may end on its own (e.g. {@code chat ended}); it then reports
 * {@link #isRunning()} false and keeps the reason as its state until {@link #stop()} replaces it
 * with {@code stopped}.
 */
public interface ChatSource {

    /** How long {@link #stop()} waits for the listener thread to end before giving up on it. */
    long STOP_JOIN_MILLIS = 1_000;

    /** Starts listening on a background thread. */
    void start();

    /**
     * Stops listening and waits briefly for the thread to end; state becomes {@code stopped} (also
     * replacing a terminal reason state). After it returns the source starts no new roster updates.
     */
    void stop();

    /** True while the source is (trying to be) connected; false once stopped or given up. */
    boolean isRunning();

    /** Current state for display, e.g. {@code connected to #channel}. Never null. */
    String getState();

    /** Called (on any thread) after every state change; the plugin refreshes open settings pages. */
    void setOnStateChange(Runnable hook);
}
