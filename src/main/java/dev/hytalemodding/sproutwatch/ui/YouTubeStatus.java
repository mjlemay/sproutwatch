package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.youtube.QuotaPacer;
import dev.hytalemodding.sproutwatch.youtube.QuotaStore;
import dev.hytalemodding.sproutwatch.youtube.YouTubeRef;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * What the page and /sproutwatch status show about YouTube. Never holds the API key itself, only
 * {@link StatusSnapshot#maskedKey its masked form}.
 *
 * @param target     "@handle", "video &lt;id&gt;" (a pasted link wins, as in YouTubeChatSource), or "" when unset
 * @param missing    what a start still needs ("an API key", ...), or null when nothing is missing
 * @param quotaUsed  units spent today (Pacific quota day)
 * @param quotaBudget units a day's chat reads may spend (daily quota minus the lookup reserve)
 * @param resetsAt   "HH:MM" of the next quota reset in the server's zone
 */
public record YouTubeStatus(boolean enabled, boolean configured, String target, String maskedKey, String missing,
                            int quotaUsed, int quotaBudget, String resetsAt) {

    /** For snapshots built without YouTube information. */
    public static final YouTubeStatus OFF = new YouTubeStatus(false, false, "", "not set", null,
        0, QuotaPacer.DAILY_QUOTA - QuotaPacer.RESERVE, "");

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param live the running source's pacer, or null: usage then comes from the config's saved
     *             quota (counted only when it is from today's quota day)
     */
    public static YouTubeStatus of(SproutwatchConfig c, QuotaPacer live, Clock clock) {
        QuotaPacer p = live;
        if (p == null) {
            p = new QuotaPacer(c.getYouTubeStreamHours(), clock);
            new QuotaStore(c, () -> {}, () -> 0L).restoreInto(p);
        }
        return new YouTubeStatus(c.isYouTubeEnabled(), c.youTubeConfigured(), target(c),
            StatusSnapshot.maskedKey(c.getYouTubeApiKey()), missing(c),
            p.usedToday(), p.chatBudget(), HH_MM.format(p.resetsAt().atZone(clock.getZone())));
    }

    /** Same as {@link SproutwatchConfig#youTubeMissing()}. */
    public static String missing(SproutwatchConfig c) {
        return c.youTubeMissing();
    }

    /** What YouTube watches: "video &lt;id&gt;" when a stream link is set, else "@handle", else "". */
    public static String target(SproutwatchConfig c) {
        Optional<String> video = YouTubeRef.parseVideoId(c.getYouTubeVideo());
        if (video.isPresent()) return "video " + video.get();
        return YouTubeRef.parseHandle(c.getYouTubeHandle()).orElse("");
    }
}
