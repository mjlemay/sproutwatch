package dev.hytalemodding.sproutwatch.hud;

/**
 * Pure sizing and progress math for the top-right panel. The client does not grow a Group to fit
 * wrapped text, so the panel height is estimated from the message (as Aetherhaven does).
 */
final class HudLayout {

    static final int RIGHT = 20;
    static final int TOP = 20;
    static final int WIDTH = 380;
    /** Roughly how many characters of the 16px bold Secondary font fit across the 352px text area. */
    static final int CHARS_PER_LINE = 39;
    static final int LINE_HEIGHT = 21;
    /** Top and bottom padding, the gap under the message and the 6px bar. */
    static final int CHROME = 16 + 18 + 10 + 6;

    private HudLayout() {}

    /** Word-wrapped line count at CHARS_PER_LINE; a word longer than a line takes whole lines of its own. */
    static int lines(String message) {
        int lines = 1;
        int used = 0;
        for (String word : message.strip().split("\\s+")) {
            int length = word.length();
            if (length == 0) continue;
            int needed = used == 0 ? length : used + 1 + length;
            if (needed <= CHARS_PER_LINE) {
                used = needed;
                continue;
            }
            if (used > 0) lines++;
            lines += (length - 1) / CHARS_PER_LINE;
            used = (length - 1) % CHARS_PER_LINE + 1;
        }
        return lines;
    }

    static int height(String message) {
        return CHROME + lines(message) * LINE_HEIGHT;
    }

    /**
     * How full the bar is: the share of the tick interval already gone, 0 when the ticker is stopped
     * (millisToNextTick < 0), 1 when the tick is due.
     */
    static float progress(long millisToNextTick, int tickSeconds) {
        if (millisToNextTick < 0 || tickSeconds <= 0) return 0f;
        double period = tickSeconds * 1000.0;
        double done = 1.0 - Math.min(millisToNextTick, period) / period;
        return (float) Math.max(0.0, Math.min(1.0, done));
    }
}
