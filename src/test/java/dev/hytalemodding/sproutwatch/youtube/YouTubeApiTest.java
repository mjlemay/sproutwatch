package dev.hytalemodding.sproutwatch.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** {@link YouTubeApi} against a local fake server. No real network calls. */
class YouTubeApiTest {

    private static final String KEY = "TESTKEY123";

    /** One canned reply. */
    private record Reply(int status, String body, long delayMillis) {
        Reply(int status, String body) {
            this(status, body, 0);
        }
    }

    /** One recorded request. */
    private record Req(String path, String rawQuery, Map<String, String> params, String accept, String keyHeader) {
    }

    private HttpServer server;
    private String base;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/youtube/v3/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
    }

    @AfterEach
    void stop() {
        server.stop(0);
        synchronized (requests) {
            for (Req r : requests) {
                assertEquals(KEY, r.keyHeader(), "key header on " + r.path());
                assertFalse(r.params().containsKey("key"), "key in query of " + r.path());
                // (the hygiene test passes the key string as a handle/token on purpose, so only key= is checked)
                assertFalse(String.valueOf(r.rawQuery()).contains("key=" + KEY), "key in query of " + r.path());
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath().substring("/youtube/v3/".length());
        String raw = ex.getRequestURI().getRawQuery();
        requests.add(new Req(path, raw, decode(raw), ex.getRequestHeaders().getFirst("Accept"),
                ex.getRequestHeaders().getFirst("X-Goog-Api-Key")));
        Reply r = replies.getOrDefault(path, new Reply(404, "{\"error\":{\"code\":404,\"message\":\"nope\",\"errors\":[]}}"));
        if (r.delayMillis() > 0) {
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        try {
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            }
        } catch (IOException ignored) {
            // client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private YouTubeApi api() {
        return new YouTubeApi(KEY, base, HttpClient.newHttpClient());
    }

    private void reply(String path, int status, String body) {
        replies.put(path, new Reply(status, body));
    }

    private static String error(int code, String reason, String message) {
        return """
                {"error":{"code":%d,"message":"%s","errors":[{"message":"%s","domain":"youtube","reason":"%s"}]}}"""
                .formatted(code, message, message, reason);
    }

    private static String fixture() throws IOException {
        try (InputStream in = YouTubeApiTest.class.getResourceAsStream("/youtube/chat_page.json")) {
            assertNotNull(in, "fixture missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YtException.Kind kindOf(Executable call) {
        return assertThrows(YtException.class, call).kind();
    }

    // ---- channels ----

    @Test
    void handleResolvesToChannelId() throws Exception {
        reply("channels", 200, "{\"items\":[{\"kind\":\"youtube#channel\",\"id\":\"UCabc\"}]}");

        assertEquals("UCabc", api().channelIdForHandle("@Name"));

        Req r = requests.get(0);
        assertEquals("channels", r.path());
        assertTrue(r.rawQuery().contains("forHandle=%40Name"), r.rawQuery());
        assertEquals("id", r.params().get("part"));
        assertEquals("@Name", r.params().get("forHandle"));
        assertEquals(KEY, r.keyHeader());
        assertEquals("application/json", r.accept());
    }

    @Test
    void handleWithoutAtGetsOne() throws Exception {
        reply("channels", 200, "{\"items\":[{\"id\":\"UCabc\"}]}");
        api().channelIdForHandle("Name");
        assertEquals("@Name", requests.get(0).params().get("forHandle"));
    }

    @Test
    void handleNotFound() {
        reply("channels", 200, "{\"kind\":\"youtube#channelListResponse\",\"pageInfo\":{\"totalResults\":0}}");
        assertEquals(YtException.Kind.NOT_FOUND, kindOf(() -> api().channelIdForHandle("@ghost")));
        reply("channels", 200, "{\"items\":[]}");
        assertEquals(YtException.Kind.NOT_FOUND, kindOf(() -> api().channelIdForHandle("@ghost")));
    }

    @Test
    void queryValuesAreUrlEncoded() throws Exception {
        reply("channels", 200, "{\"items\":[{\"id\":\"UCabc\"}]}");
        api().channelIdForHandle("@a&b=c é");
        Req r = requests.get(0);
        assertEquals("@a&b=c é", r.params().get("forHandle"));
        assertFalse(r.params().containsKey("b"));
    }

    // ---- search / videos ----

    @Test
    void noLiveVideoIsEmpty() throws Exception {
        reply("search", 200, "{\"items\":[]}");
        assertEquals(Optional.empty(), api().liveVideoId("UCabc"));

        Req r = requests.get(0);
        assertEquals("search", r.path());
        assertEquals("id", r.params().get("part"));
        assertEquals("UCabc", r.params().get("channelId"));
        assertEquals("live", r.params().get("eventType"));
        assertEquals("video", r.params().get("type"));
        assertEquals(KEY, r.keyHeader());
    }

    @Test
    void oneLiveVideoNeedsNoVideosCall() throws Exception {
        reply("search", 200, "{\"items\":[{\"id\":{\"kind\":\"youtube#video\",\"videoId\":\"vid1\"}}]}");
        assertEquals(Optional.of("vid1"), api().liveVideoId("UCabc"));
        assertEquals(1, requests.size());
    }

    @Test
    void severalLiveVideosPickLatestStart() throws Exception {
        reply("search", 200, """
                {"items":[{"id":{"videoId":"old"}},{"id":{"videoId":"newest"}},{"id":{"videoId":"mid"}}]}""");
        reply("videos", 200, """
                {"items":[
                  {"id":"old","liveStreamingDetails":{"actualStartTime":"2026-09-01T10:00:00Z"}},
                  {"id":"newest","liveStreamingDetails":{"actualStartTime":"2026-10-03T18:30:00Z"}},
                  {"id":"mid","liveStreamingDetails":{"actualStartTime":"2026-10-02T09:00:00Z"}}
                ]}""");

        assertEquals(Optional.of("newest"), api().liveVideoId("UCabc"));

        assertEquals(2, requests.size());
        Req v = requests.get(1);
        assertEquals("videos", v.path());
        assertEquals("liveStreamingDetails", v.params().get("part"));
        assertEquals("old,newest,mid", v.params().get("id"));
    }

    @Test
    void severalLiveVideosWithoutStartTimesFallBackToFirst() throws Exception {
        reply("search", 200, "{\"items\":[{\"id\":{\"videoId\":\"a\"}},{\"id\":{\"videoId\":\"b\"}}]}");
        reply("videos", 200, "{\"items\":[{\"id\":\"a\",\"liveStreamingDetails\":{}},{\"id\":\"b\"}]}");
        assertEquals(Optional.of("a"), api().liveVideoId("UCabc"));
    }

    @Test
    void activeLiveChatIdPresent() throws Exception {
        reply("videos", 200, """
                {"items":[{"id":"vid1","liveStreamingDetails":{"actualStartTime":"2026-10-03T18:30:00Z","activeLiveChatId":"CHAT1"}}]}""");
        assertEquals("CHAT1", api().activeLiveChatId("vid1"));
        Req r = requests.get(0);
        assertEquals("videos", r.path());
        assertEquals("liveStreamingDetails", r.params().get("part"));
        assertEquals("vid1", r.params().get("id"));
    }

    @Test
    void activeLiveChatIdAbsentIsChatEnded() {
        reply("videos", 200, """
                {"items":[{"id":"vid1","liveStreamingDetails":{"actualStartTime":"2026-10-03T18:30:00Z","actualEndTime":"2026-10-03T20:00:00Z"}}]}""");
        assertEquals(YtException.Kind.CHAT_ENDED, kindOf(() -> api().activeLiveChatId("vid1")));
        reply("videos", 200, "{\"items\":[{\"id\":\"vid1\"}]}");
        assertEquals(YtException.Kind.CHAT_ENDED, kindOf(() -> api().activeLiveChatId("vid1")));
    }

    @Test
    void activeLiveChatIdNoItemsIsNotFound() {
        reply("videos", 200, "{\"items\":[]}");
        assertEquals(YtException.Kind.NOT_FOUND, kindOf(() -> api().activeLiveChatId("nope")));
    }

    // ---- liveChat/messages ----

    @Test
    void firstChatPageHasNoPageToken() throws Exception {
        reply("liveChat/messages", 200, fixture());

        ChatPage page = api().chatPage("CHAT1", null);

        assertEquals(5, page.messages().size());
        assertEquals("NEXTTOKEN", page.nextPageToken());
        assertEquals(1901, page.pollingIntervalMillis());
        Req r = requests.get(0);
        assertEquals("liveChat/messages", r.path());
        assertEquals("CHAT1", r.params().get("liveChatId"));
        assertEquals("snippet,authorDetails", r.params().get("part"));
        assertFalse(r.params().containsKey("pageToken"));
        assertEquals(KEY, r.keyHeader());
    }

    @Test
    void nextChatPagePassesEncodedToken() throws Exception {
        reply("liveChat/messages", 200, fixture());

        api().chatPage("CHAT1", "tok+/=&x");

        Req r = requests.get(0);
        assertEquals("tok+/=&x", r.params().get("pageToken"));
        assertTrue(r.rawQuery().contains("pageToken=tok%2B%2F%3D%26x"), r.rawQuery());
    }

    // ---- error mapping ----

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(403, error(403, "quotaExceeded", "The request cannot be completed because you have exceeded your quota."), YtException.Kind.QUOTA_EXCEEDED),
                Arguments.of(403, error(403, "dailyLimitExceeded", "Daily Limit Exceeded"), YtException.Kind.QUOTA_EXCEEDED),
                Arguments.of(403, error(403, "rateLimitExceeded", "Rate Limit Exceeded"), YtException.Kind.TRANSIENT),
                Arguments.of(403, error(403, "userRateLimitExceeded", "User Rate Limit Exceeded"), YtException.Kind.TRANSIENT),
                Arguments.of(429, "", YtException.Kind.TRANSIENT),
                Arguments.of(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}", YtException.Kind.TRANSIENT),
                Arguments.of(429, error(429, "quotaExceeded", "x"), YtException.Kind.TRANSIENT),
                Arguments.of(408, error(408, "keyInvalid", "x"), YtException.Kind.TRANSIENT),
                Arguments.of(408, "", YtException.Kind.TRANSIENT),
                Arguments.of(400, error(400, "keyInvalid", "Bad Request"), YtException.Kind.KEY_INVALID),
                Arguments.of(400, error(400, "badRequest", "API key not valid. Please pass a valid API key."), YtException.Kind.KEY_INVALID),
                Arguments.of(403, error(403, "forbidden", "The request is missing a valid API key."), YtException.Kind.KEY_INVALID),
                Arguments.of(301, "", YtException.Kind.REJECTED),
                Arguments.of(302, "<html>moved</html>", YtException.Kind.REJECTED),
                Arguments.of(200, deep(100_000), YtException.Kind.TRANSIENT),
                Arguments.of(400, deep(100_000), YtException.Kind.REJECTED),
                Arguments.of(403, error(403, "accessNotConfigured", "YouTube Data API v3 has not been used"), YtException.Kind.KEY_INVALID),
                Arguments.of(403, error(403, "ipRefererBlocked", "Requests from this referer are blocked"), YtException.Kind.KEY_INVALID),
                Arguments.of(403, error(403, "liveChatEnded", "The live chat is no longer live."), YtException.Kind.CHAT_ENDED),
                Arguments.of(403, error(403, "liveChatDisabled", "Live chat is not enabled"), YtException.Kind.CHAT_ENDED),
                Arguments.of(404, error(404, "liveChatNotFound", "The live chat could not be found"), YtException.Kind.CHAT_ENDED),
                Arguments.of(404, error(404, "notFound", "Not Found"), YtException.Kind.NOT_FOUND),
                Arguments.of(404, "", YtException.Kind.NOT_FOUND),
                Arguments.of(500, error(500, "backendError", "Backend Error"), YtException.Kind.TRANSIENT),
                Arguments.of(503, "<html>Service Unavailable</html>", YtException.Kind.TRANSIENT),
                Arguments.of(400, error(400, "invalidPageToken", "bad token"), YtException.Kind.REJECTED),
                Arguments.of(400, error(400, "badRequest", "Invalid value for part"), YtException.Kind.REJECTED),
                Arguments.of(400, "garbage{{", YtException.Kind.REJECTED),
                Arguments.of(200, "garbage{{", YtException.Kind.TRANSIENT),
                Arguments.of(200, "", YtException.Kind.TRANSIENT));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void errorMapping(int status, String body, YtException.Kind expected) {
        reply("liveChat/messages", status, body);
        reply("channels", status, body);
        reply("videos", status, body);
        reply("search", status, body);

        assertEquals(expected, kindOf(() -> api().chatPage("CHAT1", null)), "chatPage");
        assertEquals(expected, kindOf(() -> api().channelIdForHandle("@x")), "channels");
        assertEquals(expected, kindOf(() -> api().activeLiveChatId("v")), "videos");
        assertEquals(expected, kindOf(() -> api().liveVideoId("UC")), "search");
    }

    @Test
    void connectionRefusedIsTransient() {
        YouTubeApi api = api();
        server.stop(0);
        assertEquals(YtException.Kind.TRANSIENT, kindOf(() -> api.chatPage("CHAT1", null)));
    }

    @Test
    void timeoutIsTransient() {
        replies.put("channels", new Reply(200, "{\"items\":[{\"id\":\"UCabc\"}]}", 2000));
        YouTubeApi api = new YouTubeApi(KEY, base, HttpClient.newHttpClient(), Duration.ofMillis(200));
        assertEquals(YtException.Kind.TRANSIENT, kindOf(() -> api.channelIdForHandle("@x")));
    }

    /** JSON nested {@code depth} objects deep (stack-overflow bait for recursive parsers). */
    private static String deep(int depth) {
        return "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
    }

    @Test
    void forbiddenWithoutKeyMentionIsChatEndedForChatAndRejectedElsewhere() {
        String body = error(403, "forbidden", "The caller does not have permission");
        for (String path : List.of("channels", "videos", "search", "liveChat/messages")) reply(path, 403, body);
        assertEquals(YtException.Kind.CHAT_ENDED, kindOf(() -> api().chatPage("CHAT1", "t")));
        assertEquals(YtException.Kind.REJECTED, kindOf(() -> api().channelIdForHandle("@x")));
        assertEquals(YtException.Kind.REJECTED, kindOf(() -> api().activeLiveChatId("v")));
        assertEquals(YtException.Kind.REJECTED, kindOf(() -> api().liveVideoId("UC")));
    }

    @Test
    void forbiddenMentioningApiKeyIsKeyInvalid() {
        reply("liveChat/messages", 403, error(403, "forbidden", "Requests with this API KEY are blocked"));
        assertEquals(YtException.Kind.KEY_INVALID, kindOf(() -> api().chatPage("CHAT1", null)));
    }

    @Test
    void severalLiveVideosFallBackToFirstWhenVideosCallIsTransient() throws Exception {
        reply("search", 200, "{\"items\":[{\"id\":{\"videoId\":\"a\"}},{\"id\":{\"videoId\":\"b\"}}]}");
        reply("videos", 503, error(503, "backendError", "Backend Error"));
        assertEquals(Optional.of("a"), api().liveVideoId("UCabc"));
    }

    @Test
    void severalLiveVideosStillFailOnNonTransientVideosError() {
        reply("search", 200, "{\"items\":[{\"id\":{\"videoId\":\"a\"}},{\"id\":{\"videoId\":\"b\"}}]}");
        reply("videos", 403, error(403, "quotaExceeded", "q"));
        assertEquals(YtException.Kind.QUOTA_EXCEEDED, kindOf(() -> api().liveVideoId("UCabc")));
    }

    @Test
    void bodyStallAfterHeadersTimesOut() {
        server.createContext("/stall/v3/", ex -> {
            try {
                ex.sendResponseHeaders(200, 1000);
                OutputStream out = ex.getResponseBody();
                out.write("{\"items\":[".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(3000);
            } catch (IOException | InterruptedException ignored) {
                // client gave up
            } finally {
                ex.close();
            }
        });
        String stallBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/stall/v3";
        YouTubeApi api = new YouTubeApi(KEY, stallBase, HttpClient.newHttpClient(), Duration.ofMillis(300));
        long t0 = System.nanoTime();
        YtException e = assertThrows(YtException.class, () -> api.channelIdForHandle("@x"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(YtException.Kind.TRANSIENT, e.kind());
        assertTrue(e.getMessage().contains("timeout"), e.getMessage());
        assertTrue(ms < 1500, "took " + ms + " ms");
    }

    @Test
    void oversizedBodyIsTransientWithoutEcho() {
        String big = "{\"items\":[{\"id\":\"" + "x".repeat(3 * 1024 * 1024) + "\"}]}";
        reply("channels", 200, big);
        YtException e = assertThrows(YtException.class, () -> api().channelIdForHandle("@x"));
        assertEquals(YtException.Kind.TRANSIENT, e.kind());
        assertTrue(e.getMessage().contains("response too large"), e.getMessage());
        assertFalse(e.getMessage().contains("xxxx"));
    }

    @Test
    void closeShutsDownOwnedClientOnly() throws Exception {
        HttpClient shared = HttpClient.newHttpClient();
        reply("channels", 200, "{\"items\":[{\"id\":\"UCabc\"}]}");
        try (YouTubeApi api = new YouTubeApi(KEY, base, shared)) {
            api.channelIdForHandle("@x");
        }
        assertFalse(shared.isTerminated(), "injected client must stay open");
        assertEquals("UCabc", new YouTubeApi(KEY, base, shared).channelIdForHandle("@x"));
        shared.close();

        YouTubeApi prod = new YouTubeApi(KEY);
        prod.close(); // owns its client: must not throw
    }

    // ---- secret hygiene ----

    @Test
    void keyNeverAppearsInAnyExceptionOrToString() {
        // Bodies that echo the key back, as a hostile or buggy server might.
        String echo = "key=" + KEY + " " + KEY;
        List<Reply> cases = List.of(
                new Reply(403, error(403, "quotaExceeded", echo)),
                new Reply(400, error(400, "keyInvalid", echo)),
                new Reply(400, error(400, "badRequest", "API key not valid " + echo)),
                new Reply(403, error(403, "liveChatEnded", echo)),
                new Reply(404, error(404, "notFound", echo)),
                new Reply(500, error(500, "backendError", echo)),
                new Reply(400, error(400, KEY, echo)),
                new Reply(400, error(400, "weird", echo)),
                new Reply(200, "garbage " + echo));
        List<YtException.Kind> seen = new ArrayList<>();
        for (Reply r : cases) {
            for (String path : List.of("channels", "videos", "search", "liveChat/messages")) replies.put(path, r);
            for (Executable call : List.<Executable>of(
                    () -> api().channelIdForHandle("@" + KEY),
                    () -> api().activeLiveChatId(KEY),
                    () -> api().chatPage(KEY, KEY))) {
                YtException e = assertThrows(YtException.class, call);
                seen.add(e.kind());
                assertNoKey(e);
            }
        }
        // Network failure path too.
        YouTubeApi api = api();
        server.stop(0);
        YtException e = assertThrows(YtException.class, () -> api.chatPage("c", null));
        seen.add(e.kind());
        assertNoKey(e);

        assertTrue(seen.containsAll(List.of(YtException.Kind.values())), "every kind exercised: " + seen);
        assertFalse(api.toString().contains(KEY), api.toString());
    }

    private static void assertNoKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(KEY), t.getMessage());
            assertFalse(t.toString().contains(KEY), t.toString());
        }
    }

    // ---- quota cost ----

    @Test
    void costOf() {
        assertEquals(1, YouTubeApi.costOf("channels"));
        assertEquals(1, YouTubeApi.costOf("videos"));
        assertEquals(1, YouTubeApi.costOf("search"));
        assertEquals(2, YouTubeApi.costOf("liveChat/messages"));
        assertThrows(IllegalArgumentException.class, () -> YouTubeApi.costOf("playlists"));
    }
}
