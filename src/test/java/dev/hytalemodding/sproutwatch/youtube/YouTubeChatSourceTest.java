package dev.hytalemodding.sproutwatch.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.SproutQueue;
import dev.hytalemodding.sproutwatch.chat.ViewerKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link YouTubeChatSource} against a local fake YouTube API (scripted replies per endpoint) and a
 * fake sleeper that records every requested delay. The sleeper returns at once while scripted
 * replies remain and parks (until stop() interrupts it) once the script is used up, which gives
 * each test a deterministic point to assert at. No real network, no real waits.
 */
@Timeout(10)
class YouTubeChatSourceTest {

    private static final String KEY = "TESTKEY123";
    private static final String CHANNEL = "UCchannel000000000000001";
    private static final String VIDEO = "ABCDEFGHIJK";
    private static final String CHAT = "LIVECHAT1";
    /** 18:00 UTC = 11:00 PDT; the quota resets at 07:00 UTC the next day. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T18:00:00Z"), ZoneOffset.UTC);
    private static final long UNTIL_RESET_MILLIS = Duration.ofHours(13).toMillis();

    private static final List<String> FIXTURE_KEYS = List.of(
            "yt:UCaaaaaaaaaaaaaaaaaaaaa1", "yt:UCbbbbbbbbbbbbbbbbbbbbb2", "yt:UCccccccccccccccccccccc3",
            "yt:UCaaaaaaaaaaaaaaaaaaaaa1", "yt:UCddddddddddddddddddddd4");
    private static final List<String> FIXTURE_NAMES = List.of(
            "Viewer One", "Viewer Two", "Viewer Three", "Viewer One", "Viewer Four");

    private record Reply(int status, String body, long delayMillis) {
        Reply(int status, String body) {
            this(status, body, 0);
        }
    }

    private record Req(String path, Map<String, String> params) {}

    private HttpServer server;
    private String base;
    private final Map<String, Deque<Reply>> script = new LinkedHashMap<>();
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean unscripted;

    private final List<Long> sleeps = new CopyOnWriteArrayList<>();
    private final Semaphore parked = new Semaphore(0);
    private final List<String> states = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private volatile Thread poller;
    /** Extra per-test behavior run inside the state-change hook. */
    private volatile Runnable hookExtra = () -> {};
    private Clock sourceClock = CLOCK;

    private DisplayNames names;
    private ChatRoster roster;
    private QuotaPacer pacer;
    private YouTubeChatSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/youtube/v3/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
        names = new DisplayNames();
        roster = new ChatRoster(Set::of);
        pacer = new QuotaPacer(6, CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.stop();
        server.stop(0);
        for (String line : logs) assertFalse(line.contains(KEY), "key leaked into log: " + line);
    }

    // ---- fake server ----

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath().substring("/youtube/v3/".length());
        requests.add(new Req(path, decode(exchange.getRequestURI().getRawQuery())));
        Reply r;
        synchronized (script) {
            Deque<Reply> q = script.get(path);
            r = q == null ? null : q.poll();
        }
        if (r == null) {
            unscripted = true;
            r = new Reply(500, "{\"error\":{\"code\":500,\"message\":\"unscripted\",\"errors\":[]}}");
        }
        if (r.delayMillis() > 0) {
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        try {
            exchange.sendResponseHeaders(r.status(), b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b);
            }
        } catch (IOException ignored) {
            // client gave up (stop during an in-flight call)
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            m.put(URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8),
                    i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void on(String path, int status, String body) {
        synchronized (script) {
            script.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Reply(status, body));
        }
    }

    private void ok(String path, String body) {
        on(path, 200, body);
    }

    private boolean drained() {
        synchronized (script) {
            return script.values().stream().allMatch(Deque::isEmpty);
        }
    }

    private void scriptHandleLookup() {
        ok("channels", "{\"items\":[{\"id\":\"" + CHANNEL + "\"}]}");
        ok("search", "{\"items\":[{\"id\":{\"kind\":\"youtube#video\",\"videoId\":\"" + VIDEO + "\"}}]}");
        scriptVideo();
    }

    private void scriptVideo() {
        ok("videos", "{\"items\":[{\"id\":\"" + VIDEO + "\",\"liveStreamingDetails\":{\"activeLiveChatId\":\"" + CHAT + "\"}}]}");
    }

    private static String error(int code, String reason) {
        return "{\"error\":{\"code\":%d,\"message\":\"m\",\"errors\":[{\"reason\":\"%s\"}]}}".formatted(code, reason);
    }

    private static String chatMessage(String channelId, String name, String text) {
        String author = name == null ? ""
                : ",\"authorDetails\":{\"channelId\":\"%s\",\"displayName\":\"%s\"}".formatted(channelId, name);
        return "{\"snippet\":{\"type\":\"textMessageEvent\",\"authorChannelId\":\"%s\",\"displayMessage\":\"%s\"}%s}"
                .formatted(channelId, text, author);
    }

    private static String page(String token, long interval, String... msgs) {
        return "{\"pollingIntervalMillis\":%d,\"nextPageToken\":\"%s\",\"items\":[%s]}"
                .formatted(interval, token, String.join(",", msgs));
    }

    private static String fixture() throws IOException {
        try (InputStream in = YouTubeChatSourceTest.class.getResourceAsStream("/youtube/chat_page.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- source plumbing ----

    private Logger logger() {
        Logger logger = Logger.getLogger("YouTubeChatSourceTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                logs.add(r.getMessage());
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) warnings.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    private YouTubeChatSource.Sleeper sleeper() {
        return millis -> {
            poller = Thread.currentThread();
            sleeps.add(millis);
            if (drained()) {
                parked.release();
                new CountDownLatch(1).await(); // until stop() interrupts
            }
        };
    }

    private YouTubeChatSource start(String handle, String video) {
        YouTubeApi api = new YouTubeApi(KEY, base, HttpClient.newHttpClient());
        source = new YouTubeChatSource(handle, video, api, pacer, roster, names, logger(), sleeper(), sourceClock);
        source.setOnStateChange(() -> {
            states.add(source.getState());
            hookExtra.run();
        });
        source.start();
        return source;
    }

    private void awaitParked() throws InterruptedException {
        assertTrue(parked.tryAcquire(5, TimeUnit.SECONDS), "poller never parked; state=" + source.getState());
    }

    private void awaitEnded() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (source.isRunning()) {
            assertTrue(System.nanoTime() < deadline, "source still running; state=" + source.getState());
            Thread.sleep(5);
        }
    }

    private static Thread pollerThread() {
        return Thread.currentThread().getName().equals("sproutwatch-youtube") ? Thread.currentThread() : null;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitDeath(Thread thread) throws InterruptedException {
        assertNotNull(thread, "poller thread not captured");
        thread.join(5_000);
        assertFalse(thread.isAlive(), "poller thread still alive");
    }

    private void awaitChatReads(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (chatReads().size() < n) {
            assertTrue(System.nanoTime() < deadline, "only " + chatReads().size() + " chat reads");
            Thread.sleep(5);
        }
    }

    private List<String> paths() {
        synchronized (requests) {
            return requests.stream().map(Req::path).toList();
        }
    }

    private List<Req> chatReads() {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path().equals("liveChat/messages")).toList();
        }
    }

    private static long budgetFloor() {
        return 4_400; // 6 h at 2 units over a 9,800-unit budget, ~4.41 s
    }

    // ---- tests ----

    @Test
    void resolvesHandleToChatInOrderAndRecordsCosts() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", fixture());
        start("@Mertie", null);
        awaitParked();

        assertEquals(List.of("channels", "search", "videos", "liveChat/messages"), paths());
        assertEquals("@Mertie", requests.get(0).params().get("forHandle"));
        assertEquals(CHANNEL, requests.get(1).params().get("channelId"));
        assertEquals(VIDEO, requests.get(2).params().get("id"));
        assertEquals(CHAT, requests.get(3).params().get("liveChatId"));
        assertNull(requests.get(3).params().get("pageToken"));
        assertEquals(1 + 1 + 1 + 2, pacer.usedToday());
        assertEquals("connected to YouTube (@Mertie)", source.getState());
        assertTrue(source.isRunning());
        assertEquals(List.of(YouTubeChatSource.FINDING, "connected to YouTube (@Mertie)"), states);
        assertFalse(unscripted);
    }

    @Test
    void firstPageBacklogIsSkipped() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", fixture());
        start("@Mertie", null);
        awaitParked();

        assertEquals(0, roster.size());
        assertEquals(0, roster.queue().size());
        for (String key : FIXTURE_KEYS) assertEquals(key, names.nameFor(key));
    }

    @Test
    void laterPageIsAppliedInOrder() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000, chatMessage("UCbacklog", "Old Timer", "!sprout")));
        ok("liveChat/messages", fixture());
        start("@Mertie", null);
        awaitParked();

        List<Req> reads = chatReads();
        assertEquals(2, reads.size());
        assertEquals("T1", reads.get(1).params().get("pageToken"));
        assertEquals(Set.copyOf(FIXTURE_KEYS), roster.snapshot().keySet());
        assertFalse(roster.snapshot().containsKey("yt:UCbacklog"));
        for (int i = 0; i < FIXTURE_KEYS.size(); i++) assertEquals(FIXTURE_NAMES.get(i), names.nameFor(FIXTURE_KEYS.get(i)));
        assertEquals(List.of("yt:UCbbbbbbbbbbbbbbbbbbbbb2", "yt:UCaaaaaaaaaaaaaaaaaaaaa1"), roster.queue().snapshot());
        assertEquals(1 + 1 + 1 + 2 + 2, pacer.usedToday());
    }

    @Test
    void displayNameIsStoredBeforeTheRosterSeesTheViewer() throws Exception {
        // ChatRoster is final; its ignore-list supplier is read at the start of every apply(), which
        // makes it a probe for what DisplayNames holds at that moment.
        AtomicInteger call = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        roster = new ChatRoster(() -> {
            seen.add(names.nameFor(FIXTURE_KEYS.get(call.getAndIncrement())));
            return Set.of();
        });
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", fixture());
        start("@Mertie", null);
        awaitParked();

        assertEquals(FIXTURE_NAMES, seen);
    }

    @Test
    void nullDisplayNameFallsBackToTheKey() throws Exception {
        String key = ViewerKey.youtube("UCnoname");
        names.put(key, "Stale Name");
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", page("T2", 1000, chatMessage("UCnoname", null, "hi")));
        start("@Mertie", null);
        awaitParked();

        assertTrue(roster.snapshot().containsKey(key));
        assertEquals(key, names.nameFor(key));
    }

    @Test
    void sleepsAtLeastTheSuggestionAndThePacerDelay() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1901));
        ok("liveChat/messages", page("T2", 30_000));
        start("@Mertie", null);
        awaitParked();

        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0) >= Math.max(1901, budgetFloor()), "first sleep " + sleeps.get(0));
        assertTrue(sleeps.get(1) >= 30_000, "second sleep " + sleeps.get(1));
    }

    @Test
    void spentPacerBudgetWaitsUntilResetWithoutAnyCall() throws Exception {
        pacer = new QuotaPacer(10, 0, 2, 6, CLOCK);
        pacer.recordCall(9); // less than one read left: spent, so not even the lookups run
        start("@Mertie", null);
        awaitParked();

        assertEquals("quota exhausted (resets 07:00)", source.getState());
        assertTrue(source.isRunning(), "waits for the reset instead of stopping");
        assertEquals(List.of(), paths());
        assertTrue(sleeps.get(0) >= UNTIL_RESET_MILLIS, "sleep " + sleeps.get(0));
    }

    @Test
    void quotaExceededWaitsUntilResetThenResumesFromAFreshFirstPage() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        on("liveChat/messages", 403, error(403, "quotaExceeded"));
        ok("liveChat/messages", page("T2", 1000, chatMessage("UCbacklog", "Old", "!sprout")));
        ok("liveChat/messages", page("T3", 1000, chatMessage("UCnew", "New", "hi")));
        start("@Mertie", null);
        awaitParked();

        assertTrue(states.contains("quota exhausted (resets 07:00)"), states.toString());
        assertTrue(sleeps.stream().anyMatch(s -> s >= UNTIL_RESET_MILLIS), sleeps.toString());
        List<Req> reads = chatReads();
        assertEquals(4, reads.size());
        assertEquals("T1", reads.get(1).params().get("pageToken"));
        assertNull(reads.get(2).params().get("pageToken"), "fresh first page after the reset");
        assertEquals("T2", reads.get(3).params().get("pageToken"));
        assertEquals(Set.of("yt:UCnew"), roster.snapshot().keySet());
        assertEquals("connected to YouTube (@Mertie)", source.getState());
    }

    @Test
    void rejectedKeyStopsWithNoFurtherCalls() throws Exception {
        on("channels", 400, error(400, "keyInvalid"));
        start("@Mertie", null);
        awaitEnded();

        assertEquals(YouTubeChatSource.KEY_REJECTED, source.getState());
        Thread.sleep(50);
        assertEquals(List.of("channels"), paths());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void endedPageAppliesItsMessagesThenStops() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", "{\"pollingIntervalMillis\":1000,\"offlineAt\":\"2026-10-03T19:00:00Z\",\"items\":["
                + chatMessage("UClast", "Last", "bye") + "]}");
        start("@Mertie", null);
        awaitEnded();

        assertEquals(YouTubeChatSource.CHAT_ENDED, source.getState());
        assertTrue(roster.snapshot().containsKey("yt:UClast"));
        assertEquals(2, chatReads().size());
    }

    @Test
    void chatEndedErrorStops() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        on("liveChat/messages", 403, error(403, "liveChatEnded"));
        start("@Mertie", null);
        awaitEnded();

        assertEquals(YouTubeChatSource.CHAT_ENDED, source.getState());
        assertFalse(unscripted);
    }

    @Test
    void transientErrorsBackOffDoublingToFiveMinutesAndRecover() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        for (int i = 0; i < 8; i++) on("liveChat/messages", 503, "{}");
        ok("liveChat/messages", page("T2", 1000));
        on("liveChat/messages", 503, "{}");
        start("", VIDEO);
        awaitParked();

        assertEquals(11, sleeps.size(), sleeps.toString());
        assertTrue(sleeps.get(0) >= budgetFloor());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
                sleeps.subList(1, 9));
        assertTrue(sleeps.get(9) >= budgetFloor(), "recovered: paced again");
        assertEquals(5_000L, sleeps.get(10), "backoff reset after success");
        String connected = "connected to YouTube (video " + VIDEO + ")";
        assertEquals(List.of(YouTubeChatSource.FINDING, connected, YouTubeChatSource.RECONNECTING, connected,
                YouTubeChatSource.RECONNECTING), states);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("HTTP 503")), warnings.toString());
    }

    @Test
    void rejectedOnceRestartsFromAFreshFirstPageSkippingBacklog() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000, chatMessage("UColdA", "A", "!sprout")));
        on("liveChat/messages", 400, error(400, "invalidPageToken"));
        ok("liveChat/messages", page("T2", 1000, chatMessage("UColdB", "B", "!sprout")));
        ok("liveChat/messages", fixture());
        start("@Mertie", null);
        awaitParked();

        List<Req> reads = chatReads();
        assertEquals(4, reads.size());
        assertNull(reads.get(0).params().get("pageToken"));
        assertEquals("T1", reads.get(1).params().get("pageToken"));
        assertNull(reads.get(2).params().get("pageToken"));
        assertEquals("T2", reads.get(3).params().get("pageToken"));
        assertEquals(Set.copyOf(FIXTURE_KEYS), roster.snapshot().keySet());
        assertTrue(source.isRunning());
    }

    @Test
    void rejectedTwiceInARowStops() throws Exception {
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        on("liveChat/messages", 400, error(400, "invalidPageToken"));
        on("liveChat/messages", 400, error(400, "invalidPageToken"));
        start("@Mertie", null);
        awaitEnded();

        assertEquals(YouTubeChatSource.REJECTED, source.getState());
        assertEquals(3, chatReads().size());
    }

    @Test
    void notLiveAfterTenRetriesAMinuteApart() throws Exception {
        ok("channels", "{\"items\":[{\"id\":\"" + CHANNEL + "\"}]}");
        for (int i = 0; i < 11; i++) ok("search", "{\"items\":[]}");
        start("@Mertie", null);
        awaitEnded();

        assertEquals(YouTubeChatSource.NOT_LIVE, source.getState());
        assertEquals(Collections.nCopies(10, 60_000L), sleeps);
        assertEquals(1, paths().stream().filter("channels"::equals).count(), "channel ID resolved once");
        assertEquals(11, paths().stream().filter("search"::equals).count());
        assertEquals(List.of(YouTubeChatSource.FINDING, YouTubeChatSource.NO_STREAM, YouTubeChatSource.NOT_LIVE), states);
        assertEquals(12, pacer.usedToday());
        assertFalse(unscripted);
    }

    @Test
    void unknownHandleIsRetriedAsNotFound() throws Exception {
        on("channels", 404, error(404, "channelNotFound"));
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        start("@Mertie", null);
        awaitParked();

        assertEquals(60_000L, sleeps.get(0));
        assertEquals("connected to YouTube (@Mertie)", source.getState());
        assertEquals(1 + 1 + 1 + 1 + 2, pacer.usedToday(), "the failed call is charged too");
    }

    @Test
    void videoOverrideSkipsHandleLookups() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        start("@Ignored", "https://www.youtube.com/watch?v=" + VIDEO);
        awaitParked();

        assertEquals(List.of("videos", "liveChat/messages"), paths());
        assertEquals("connected to YouTube (video " + VIDEO + ")", source.getState());
    }

    @Test
    void stopDuringASleepReturnsPromptly() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        start("", VIDEO);
        awaitParked();

        long t0 = System.nanoTime();
        source.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(tookMillis < 1_000, "stop took " + tookMillis + " ms");
        assertFalse(source.isRunning());
        assertEquals(YouTubeChatSource.STOPPED, source.getState());
        assertEquals(YouTubeChatSource.STOPPED, states.get(states.size() - 1));
        assertFalse(poller.isAlive(), "the source's own thread has ended");
    }

    @Test
    void startIsIdempotent() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        start("", VIDEO);
        source.start();
        awaitParked();

        assertEquals(List.of("videos", "liveChat/messages"), paths());
    }

    @Test
    void rosterFailureDoesNotKillTheLoop() throws Exception {
        AtomicInteger call = new AtomicInteger();
        Supplier<Set<String>> flaky = () -> {
            if (call.getAndIncrement() == 0) throw new IllegalStateException("boom");
            return Set.of();
        };
        roster = new ChatRoster(flaky, () -> "!sprout", new SproutQueue());
        scriptHandleLookup();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", page("T2", 1000, chatMessage("UCfirst", "First", "hi"), chatMessage("UCsecond", "Second", "!sprout")));
        ok("liveChat/messages", page("T3", 1000, chatMessage("UCthird", "Third", "hey")));
        start("@Mertie", null);
        awaitParked();

        assertEquals(Set.of("yt:UCsecond", "yt:UCthird"), roster.snapshot().keySet());
        assertEquals(List.of("yt:UCsecond"), roster.queue().snapshot());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Roster update failed")), warnings.toString());
        assertEquals("connected to YouTube (@Mertie)", source.getState());
        assertEquals(3, chatReads().size());
    }

    @Test
    void stopThatTimesOutOnABlockedHookNeverAppliesLate() throws Exception {
        // The plugin's hook can block on a lock that stop()'s caller holds. stop() must give up
        // after JOIN_MILLIS, and once the plugin has cleared the roster nothing may land in it.
        CountDownLatch hookBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger connected = new AtomicInteger();
        hookExtra = () -> {
            if (pollerThread() != null && source.getState().startsWith("connected")
                    && connected.incrementAndGet() == 2) {
                hookBlocked.countDown();
                awaitUninterruptibly(release);
            }
        };
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        on("liveChat/messages", 503, "{}");
        ok("liveChat/messages", page("T2", 1000, chatMessage("UCearly", "Early", "!sprout")));
        ok("liveChat/messages", page("T3", 1000, chatMessage("UClate", "Late", "!sprout")));
        start("", VIDEO);
        assertTrue(hookBlocked.await(5, TimeUnit.SECONDS));
        Thread thread = poller;

        long t0 = System.nanoTime();
        source.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(tookMillis >= YouTubeChatSource.JOIN_MILLIS - 50 && tookMillis < 1_100 + 200, "stop took " + tookMillis + " ms");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not end")), warnings.toString());
        roster.clear();
        names.clear();
        release.countDown();
        awaitDeath(thread);

        assertEquals(0, roster.size(), "nothing applied after stop");
        assertEquals(0, roster.queue().size());
        assertEquals(3, chatReads().size(), "no read after stop");
        assertEquals(YouTubeChatSource.STOPPED, source.getState());
    }

    @Test
    void stopDuringAnInFlightCallReturnsPromptlyAndAppliesNothing() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        synchronized (script) {
            script.get("liveChat/messages").add(new Reply(200, page("T2", 1000, chatMessage("UCslow", "Slow", "!sprout")), 3_000));
        }
        sourceClock = CLOCK;
        start("", VIDEO);
        awaitChatReads(2);
        Thread.sleep(50); // let the poller block in the HTTP call
        Thread thread = poller;

        long t0 = System.nanoTime();
        source.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(tookMillis < 1_000, "stop took " + tookMillis + " ms");
        awaitDeath(thread);
        assertEquals(0, roster.size());
        assertEquals(YouTubeChatSource.STOPPED, source.getState());
    }

    @Test
    void stopFromInsideTheHookDoesNotDeadlock() throws Exception {
        hookExtra = () -> {
            if (source.getState().startsWith("connected")) source.stop();
        };
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", page("T2", 1000));
        start("", VIDEO);
        awaitEnded();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!YouTubeChatSource.STOPPED.equals(states.get(states.size() - 1))) {
            assertTrue(System.nanoTime() < deadline, states.toString());
            Thread.sleep(5);
        }
        Thread.sleep(50);

        assertEquals(YouTubeChatSource.STOPPED, source.getState());
        assertEquals(1, chatReads().size());
        assertTrue(sleeps.isEmpty(), "no further polling");
    }

    @Test
    void startAgainAfterATerminalStateBeginsAFreshRunSkippingBacklog() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        ok("liveChat/messages", "{\"pollingIntervalMillis\":1000,\"offlineAt\":\"2026-10-03T19:00:00Z\",\"items\":[]}");
        start("", VIDEO);
        awaitEnded();
        assertEquals(YouTubeChatSource.CHAT_ENDED, source.getState());

        scriptVideo();
        ok("liveChat/messages", page("R1", 1000, chatMessage("UCbacklog", "Old", "!sprout")));
        ok("liveChat/messages", page("R2", 1000, chatMessage("UCnew", "New", "!sprout")));
        source.start();
        awaitParked();

        assertEquals(List.of("videos", "liveChat/messages", "liveChat/messages",
                "videos", "liveChat/messages", "liveChat/messages"), paths());
        assertNull(chatReads().get(2).params().get("pageToken"));
        assertEquals(Set.of("yt:UCnew"), roster.snapshot().keySet());
        assertEquals("connected to YouTube (video " + VIDEO + ")", source.getState());
        assertTrue(source.isRunning());
    }

    @Test
    void notFoundWhilePollingReResolvesTheStream() throws Exception {
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        on("liveChat/messages", 404, error(404, "notFound"));
        scriptVideo();
        ok("liveChat/messages", page("T2", 1000, chatMessage("UCbacklog", "Old", "hi")));
        ok("liveChat/messages", page("T3", 1000, chatMessage("UCnew", "New", "hi")));
        start("", VIDEO);
        awaitParked();

        assertEquals(List.of("videos", "liveChat/messages", "liveChat/messages",
                "videos", "liveChat/messages", "liveChat/messages"), paths());
        assertEquals(5_000L, sleeps.get(1), "backoff before re-resolving");
        assertTrue(states.contains(YouTubeChatSource.RECONNECTING), states.toString());
        assertEquals(Set.of("yt:UCnew"), roster.snapshot().keySet());
    }

    @Test
    void throwableOutsideTheRosterIsLoggedAndBackedOff() throws Exception {
        AtomicInteger millisCalls = new AtomicInteger();
        sourceClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return CLOCK.getZone(); }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return CLOCK.instant(); }
            @Override public long millis() {
                switch (millisCalls.incrementAndGet()) {
                    case 1 -> throw new IllegalStateException("http://example.invalid/?key=SECRETISH");
                    case 2 -> throw new AssertionError("detail");
                    default -> { return CLOCK.millis(); }
                }
            }
        };
        scriptVideo();
        ok("liveChat/messages", page("T1", 1000));
        for (int i = 0; i < 3; i++) ok("liveChat/messages", page("T2", 1000, chatMessage("UCa", "A", "hi")));
        start("", VIDEO);
        awaitParked();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("java.lang.IllegalStateException")), warnings.toString());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("SECRETISH")), "exception message not logged");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("java.lang.AssertionError: detail at ")), warnings.toString());
        assertEquals(List.of(5_000L, 10_000L), sleeps.subList(1, 3));
        assertEquals(List.of("T1", "T1", "T1"),
                chatReads().subList(1, 4).stream().map(r -> r.params().get("pageToken")).toList());
        assertEquals(Set.of("yt:UCa"), roster.snapshot().keySet());
        assertEquals("connected to YouTube (video " + VIDEO + ")", source.getState());
    }

    @Test
    void errorsAreDescribedWithMessageButExceptionsByClassOnly() {
        assertEquals("java.lang.IllegalStateException",
                YouTubeChatSource.describe(new IllegalStateException("key=abc")));
        assertTrue(YouTubeChatSource.describe(new OutOfMemoryError("heap")).startsWith("java.lang.OutOfMemoryError: heap at "));
    }
}
