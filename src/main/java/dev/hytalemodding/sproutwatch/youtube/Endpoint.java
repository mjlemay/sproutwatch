package dev.hytalemodding.sproutwatch.youtube;

/**
 * The YouTube Data API v3 endpoints this mod calls, with the quota units each call is charged.
 *
 * Costs, measured 2026-10-03 on the Cloud Console quota page:
 * - channels and videos list are 1 unit each.
 * - A liveChat/messages read costs 1 or 2 units; budgeting the upper bound keeps us safe, and
 *       {@link QuotaPacer#COST_PER_POLL} is this cost.
 * - search.list is documented by Google at 100 units, but the measured startup sequence (1 channels
 *       + 1 search + 1 videos + 2 chat reads) was 7 units total, so search is not charged 100 against
 *       the main daily quota and counts 1 here.
 *
 * Risk to watch: if real streams show bigger jumps on the quota page, raise SEARCH's cost to 100;
 * the 200-unit {@link QuotaPacer#RESERVE} would then cover only two stream starts a day.
 */
public enum Endpoint {
    CHANNELS("channels", 1),
    SEARCH("search", 1),
    VIDEOS("videos", 1),
    CHAT_MESSAGES("liveChat/messages", 2);

    private final String path;
    private final int cost;

    Endpoint(String path, int cost) {
        this.path = path;
        this.cost = cost;
    }

    /** The path under the API base URL, for example {@code liveChat/messages}. */
    public String path() {
        return path;
    }

    /** Quota units charged per call, for the pacer. */
    public int cost() {
        return cost;
    }
}
