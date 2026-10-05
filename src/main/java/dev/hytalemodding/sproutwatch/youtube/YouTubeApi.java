package dev.hytalemodding.sproutwatch.youtube;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * Thin YouTube Data API v3 client (API key only, no OAuth).
 *
 * Use one shared instance per plugin: each instance built with {@link #YouTubeApi(String)}
 * owns an {@link HttpClient} (with its own selector thread and pool); {@link #close()} releases it.
 * An instance built with an injected client never closes that client. Thread-safe (immutable state;
 * HttpClient is thread-safe).
 *
 * Secret hygiene: the key travels only in the {@code X-Goog-Api-Key} request header, never in the
 * URL (so it stays out of JDK HttpClient URI logging and proxy logs). Exception messages are built
 * from the call name, HTTP status and API reason code, passed through {@link #redact(String)} as
 * defense in depth; URLs and bodies are never included and causes are never chained. No logging here
 * (callers log).
 *
 * Robustness: the whole exchange (headers and body) is bounded by the timeout, bodies are
 * capped at {@link #MAX_BODY_BYTES}, and pathologically nested JSON cannot escape as a
 * {@link StackOverflowError}.
 */
public final class YouTubeApi implements AutoCloseable {

    public static final String DEFAULT_BASE = "https://www.googleapis.com/youtube/v3";
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Largest response body accepted; a real chat page is a few tens of KB. */
    static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    private static final Set<String> QUOTA_REASONS = Set.of("quotaExceeded", "dailyLimitExceeded");
    /** Short-term throttling: back off and retry, unlike the daily quota. */
    private static final Set<String> RATE_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded");
    private static final Set<String> KEY_REASONS = Set.of("keyInvalid", "accessNotConfigured", "ipRefererBlocked");
    /** Generic reasons that mean "bad key" only when the message mentions the API key. */
    private static final Set<String> KEY_IF_MENTIONED_REASONS = Set.of("badRequest", "forbidden");
    private static final Set<String> CHAT_ENDED_REASONS = Set.of("liveChatEnded", "liveChatDisabled", "liveChatNotFound");
    /** A reason code is quoted in messages only if it looks like one. */
    private static final Pattern REASON_SHAPE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern KEY_PARAM = Pattern.compile("(?i)(key=)[^&\\s\"]*");

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final boolean ownsClient;

    /** Injected client (not closed by {@link #close()}). */
    public YouTubeApi(String apiKey, String baseUrl, HttpClient http) {
        this(apiKey, baseUrl, http, TIMEOUT, false);
    }

    /** Production: {@link #DEFAULT_BASE}, 10 s connect and request timeouts; owns its client. */
    public YouTubeApi(String apiKey) {
        this(apiKey, DEFAULT_BASE, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), TIMEOUT, true);
    }

    /** Tests: shorter per-request timeout. */
    YouTubeApi(String apiKey, String baseUrl, HttpClient http, Duration timeout) {
        this(apiKey, baseUrl, http, timeout, false);
    }

    private YouTubeApi(String apiKey, String baseUrl, HttpClient http, Duration timeout, boolean ownsClient) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("YouTube API key is empty");
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.timeout = timeout;
        this.ownsClient = ownsClient;
    }

    /** Closes the HTTP client if this instance created it; an injected client is left open. */
    @Override
    public void close() {
        if (ownsClient) http.close();
    }

    /** {@code @handle} (leading @ optional) → channel ID. NOT_FOUND when no channel has that handle. */
    public String channelIdForHandle(String handle) throws YouTubeException {
        String atHandle = handle.startsWith("@") ? handle : "@" + handle;
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("part", "id");
        parameters.put("forHandle", atHandle);
        BsonDocument root = get("channelIdForHandle", Endpoint.CHANNELS, parameters);
        for (BsonDocument item : YouTubeJson.items(root)) {
            String id = YouTubeJson.stringField(item, "id");
            if (id != null && !id.isEmpty()) return id;
        }
        throw new YouTubeException(YouTubeException.Kind.NOT_FOUND, "channelIdForHandle: no channel for that handle");
    }

    /**
     * The channel's current live video, or empty when it is not live. A channel can run several live
     * streams at once; then the one with the latest {@code actualStartTime} wins (one extra videos call).
     *
     * @param chargeUnits receives the quota cost of each call this method makes, failed calls
     *                    included: the search always, the tie-break videos call when there is one
     */
    public Optional<String> liveVideoId(String channelId, IntConsumer chargeUnits) throws YouTubeException {
        Map<String, String> searchQuery = new LinkedHashMap<>();
        searchQuery.put("part", "id");
        searchQuery.put("channelId", channelId);
        searchQuery.put("eventType", "live");
        searchQuery.put("type", "video");
        BsonDocument root;
        try {
            root = get("liveVideoId", Endpoint.SEARCH, searchQuery);
        } finally {
            chargeUnits.accept(Endpoint.SEARCH.cost());
        }
        List<String> ids = new ArrayList<>();
        for (BsonDocument item : YouTubeJson.items(root)) {
            String id = YouTubeJson.stringField(YouTubeJson.childDocument(item, "id"), "videoId");
            if (id != null && !id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return Optional.empty();
        if (ids.size() == 1) return Optional.of(ids.get(0));

        Map<String, String> videosQuery = new LinkedHashMap<>();
        videosQuery.put("part", "liveStreamingDetails");
        videosQuery.put("id", String.join(",", ids));
        BsonDocument videos;
        try {
            videos = get("liveVideoId", Endpoint.VIDEOS, videosQuery);
        } catch (YouTubeException exception) {
            // Tie-break lookup failed transiently: a live video is better than none.
            if (exception.kind() == YouTubeException.Kind.TRANSIENT) return Optional.of(ids.get(0));
            throw exception;
        } finally {
            chargeUnits.accept(Endpoint.VIDEOS.cost());
        }
        String best = ids.get(0);
        Instant bestStart = null;
        for (BsonDocument item : YouTubeJson.items(videos)) {
            String id = YouTubeJson.stringField(item, "id");
            Instant start = YouTubeJson.parseInstant(YouTubeJson.stringField(YouTubeJson.childDocument(item, "liveStreamingDetails"), "actualStartTime"));
            if (id == null || start == null || !ids.contains(id)) continue;
            if (bestStart == null || start.isAfter(bestStart)) {
                best = id;
                bestStart = start;
            }
        }
        return Optional.of(best);
    }

    /** Video → active live chat ID. CHAT_ENDED when absent (stream over / chat off); NOT_FOUND when no such video. */
    public String activeLiveChatId(String videoId) throws YouTubeException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("part", "liveStreamingDetails");
        q.put("id", videoId);
        List<BsonDocument> items = YouTubeJson.items(get("activeLiveChatId", Endpoint.VIDEOS, q));
        if (items.isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.NOT_FOUND, "activeLiveChatId: no such video");
        }
        String chatId = YouTubeJson.stringField(YouTubeJson.childDocument(items.get(0), "liveStreamingDetails"), "activeLiveChatId");
        if (chatId == null || chatId.isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.CHAT_ENDED, "activeLiveChatId: video has no active live chat");
        }
        return chatId;
    }

    /** One page of chat; {@code pageToken} null for the first read. */
    public ChatPage chatPage(String liveChatId, String pageToken) throws YouTubeException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("liveChatId", liveChatId);
        q.put("part", "snippet,authorDetails");
        if (pageToken != null && !pageToken.isEmpty()) q.put("pageToken", pageToken);
        String body = fetch("chatPage", Endpoint.CHAT_MESSAGES, q);
        try {
            return YouTubeChatParser.parse(body);
        } catch (RuntimeException | StackOverflowError exception) {
            throw new YouTubeException(YouTubeException.Kind.TRANSIENT, "chatPage: unreadable response");
        }
    }

    @Override
    public String toString() {
        return "YouTubeApi[" + redact(baseUrl) + "]";
    }

    // ---- transport ----

    private BsonDocument get(String operation, Endpoint endpoint, Map<String, String> query) throws YouTubeException {
        String body = fetch(operation, endpoint, query);
        try {
            if (body == null || body.isBlank()) throw new IllegalArgumentException();
            return BsonDocument.parse(body);
        } catch (RuntimeException | StackOverflowError exception) {
            throw new YouTubeException(YouTubeException.Kind.TRANSIENT, operation + ": unreadable response");
        }
    }

    /** Performs the GET; returns the 2xx body or throws a classified, key-free exception. */
    private String fetch(String operation, Endpoint endpoint, Map<String, String> query) throws YouTubeException {
        StringBuilder url = new StringBuilder(baseUrl).append('/').append(endpoint.path());
        char separator = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            url.append(separator).append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
            separator = '&';
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .GET()
                    .build();
        } catch (IllegalArgumentException exception) {
            // Malformed base URL or header value; never echo either.
            throw new YouTubeException(YouTubeException.Kind.REJECTED, operation + ": invalid request");
        }

        // The request timeout only bounds the wait for headers; get(timeout) bounds the body too.
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_BODY_BYTES));
        HttpResponse<String> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new YouTubeException(YouTubeException.Kind.TRANSIENT, operation + ": timeout");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new YouTubeException(YouTubeException.Kind.TRANSIENT, operation + ": interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (isTooLarge(cause)) {
                throw new YouTubeException(YouTubeException.Kind.TRANSIENT, operation + ": response too large");
            }
            String what = cause instanceof java.net.http.HttpTimeoutException ? "timeout"
                    : "network error (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")";
            // Exception class name only: messages of network exceptions may quote the URL.
            throw new YouTubeException(YouTubeException.Kind.TRANSIENT, redact(operation + ": " + what));
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) return response.body();
        throw classify(operation, endpoint, status, response.body());
    }

    /** True when {@code throwable} is the limiting body handler's over-capacity failure. */
    private static boolean isTooLarge(Throwable throwable) {
        for (; throwable != null; throwable = throwable.getCause()) {
            String m = throwable.getMessage();
            if (throwable instanceof IOException && m != null) {
                String lower = m.toLowerCase(Locale.ROOT);
                if (lower.contains("exceed") || lower.contains("limit")) return true;
            }
        }
        return false;
    }

    private YouTubeException classify(String operation, Endpoint endpoint, int status, String body) {
        String reason = null;
        String message = "";
        try {
            BsonDocument errorBody = YouTubeJson.childDocument(BsonDocument.parse(body), "error");
            BsonValue errors = errorBody.get("errors");
            if (errors != null && errors.isArray() && !errors.asArray().isEmpty() && errors.asArray().get(0).isDocument()) {
                reason = YouTubeJson.stringField(errors.asArray().get(0).asDocument(), "reason");
            }
            String m = YouTubeJson.stringField(errorBody, "message");
            if (m != null) message = m;
        } catch (RuntimeException | StackOverflowError ignored) {
            // Unparsable error body: classify by status alone.
            reason = null;
            message = "";
        }
        // Matched, never copied into the exception message.
        boolean mentionsKey = message.toLowerCase(Locale.ROOT).contains("api key");

        YouTubeException.Kind kind;
        if (status >= 300 && status < 400) {
            kind = YouTubeException.Kind.REJECTED; // redirect: misconfigured base, retrying won't help
        } else if (status == 429 || status == 408) {
            kind = YouTubeException.Kind.TRANSIENT; // throttled / request timeout, whatever the body says
        } else if (reason != null && RATE_REASONS.contains(reason)) {
            kind = YouTubeException.Kind.TRANSIENT;
        } else if (reason != null && QUOTA_REASONS.contains(reason)) {
            kind = YouTubeException.Kind.QUOTA_EXCEEDED;
        } else if (reason != null && CHAT_ENDED_REASONS.contains(reason)) {
            kind = YouTubeException.Kind.CHAT_ENDED;
        } else if (reason != null && KEY_REASONS.contains(reason)) {
            kind = YouTubeException.Kind.KEY_INVALID;
        } else if (reason != null && KEY_IF_MENTIONED_REASONS.contains(reason) && mentionsKey) {
            kind = YouTubeException.Kind.KEY_INVALID; // e.g. Google's 400 badRequest "API key not valid"
        } else if ("forbidden".equals(reason)) {
            // Not about the key: on a chat read it means we may not read this chat (owner turned it off).
            kind = endpoint == Endpoint.CHAT_MESSAGES ? YouTubeException.Kind.CHAT_ENDED : YouTubeException.Kind.REJECTED;
        } else if (status == 404) {
            kind = YouTubeException.Kind.NOT_FOUND;
        } else if (status >= 500) {
            kind = YouTubeException.Kind.TRANSIENT;
        } else if (status >= 400) {
            kind = YouTubeException.Kind.REJECTED;
        } else {
            kind = YouTubeException.Kind.TRANSIENT; // 1xx: unexpected, treat as a hiccup
        }

        String shownReason = reason != null && REASON_SHAPE.matcher(reason).matches() ? " " + reason : "";
        return new YouTubeException(kind, redact(operation + ": HTTP " + status + shownReason));
    }

    /** Removes the API key (raw or encoded) and any {@code key=} parameter from {@code text}. */
    private String redact(String text) {
        if (text == null) return null;
        String out = text.replace(apiKey, "REDACTED").replace(urlEncode(apiKey), "REDACTED");
        return KEY_PARAM.matcher(out).replaceAll("$1REDACTED");
    }

    // ---- URL helpers ----

    private static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
