package dev.hytalemodding.sproutwatch.youtube;

/**
 * The YouTube Data API v3 endpoints this mod calls, with the quota units each call is charged.
 *
 * Costs from Google's table (developers.google.com/youtube/v3/determine_quota_cost, checked
 * 2026-10-14):
 * - channels and videos list are 1 unit each.
 * - liveChatMessages list is 5 units. {@link QuotaPacer#COST_PER_POLL} is this cost. (It was once
 *       budgeted at 2 from a Cloud Console reading, which lags and under-read; at 2 an 8 h plan ran
 *       dry about 3 h in.)
 * - search list has its own allowance of 100 calls a day and is charged 1 unit each against the
 *       main daily quota.
 */
public enum Endpoint {
    CHANNELS("channels", 1),
    SEARCH("search", 1),
    VIDEOS("videos", 1),
    CHAT_MESSAGES("liveChat/messages", 5);

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
