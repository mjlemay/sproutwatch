package dev.hytalemodding.sproutwatch.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hytalemodding.sproutwatch.chat.ChatRoster;
import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.DisplayNames;
import dev.hytalemodding.sproutwatch.chat.SproutQueue;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** {@link YouTubeService} against a local fake server. No real network calls. */
class YouTubeServiceTest {

    /** One client the service closed, and the thread that closed it. */
    private record Closed(YouTubeApi client, Thread thread) {
    }

    private HttpServer server;
    private String base;
    private HttpClient http;
    private final SproutwatchConfig config = new SproutwatchConfigAccess().fresh();
    private final AtomicInteger saves = new AtomicInteger();
    private final QuotaStore quotaStore = new QuotaStore(config, saves::incrementAndGet, System::currentTimeMillis);
    private final List<YouTubeApi> created = new CopyOnWriteArrayList<>();
    private final List<Closed> closed = new CopyOnWriteArrayList<>();
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final ChatRoster roster = new ChatRoster(Set::of, () -> "!sprout", new SproutQueue());
    private final DisplayNames names = new DisplayNames();
    private YouTubeService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
        http = HttpClient.newHttpClient();
        config.setYouTubeEnabled(true);
        config.setYouTubeApiKey("KEY_ONE");
        config.setYouTubeHandle("@streamer");
        service = new YouTubeService(() -> config, quotaStore, Logger.getLogger("YouTubeServiceTest"),
            key -> {
                YouTubeApi client = new YouTubeApi(key, base, http);
                created.add(client);
                return client;
            },
            client -> {
                closed.add(new Closed(client, Thread.currentThread()));
                closeLatch.countDown();
            },
            lookups);
    }

    @AfterEach
    void stop() {
        lookups.shutdownNow();
        server.stop(0);
        http.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = "{\"items\":[{\"id\":\"UC_viewer\"}]}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test void clientIsReusedForTheSameKeyAndRecreatedWhenTheKeyChanges() throws InterruptedException {
        assertNotNull(service.newSource(config, roster, names));
        assertNotNull(service.newSource(config, roster, names));
        assertEquals(1, created.size(), "same key reuses the client");
        assertTrue(closed.isEmpty());

        config.setYouTubeApiKey("KEY_TWO");
        assertNotNull(service.newSource(config, roster, names));
        assertEquals(2, created.size(), "a new key makes a new client");
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "the replaced client is closed");
        assertEquals(1, closed.size());
        assertSame(created.get(0), closed.get(0).client());
        assertNotSame(Thread.currentThread(), closed.get(0).thread(), "closed off the calling thread");
    }

    @Test void stoppedClosesTheClientOffThreadOnceYouTubeIsOff() throws InterruptedException {
        assertNotNull(service.newSource(config, roster, names));
        config.setYouTubeEnabled(false);
        service.stopped();
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertSame(created.get(0), closed.get(0).client());
        assertNotSame(Thread.currentThread(), closed.get(0).thread());
    }

    @Test void stoppedKeepsTheClientWhileYouTubeIsStillConfigured() {
        assertNotNull(service.newSource(config, roster, names));
        service.stopped();
        assertNotNull(service.newSource(config, roster, names));
        assertEquals(1, created.size());
        assertTrue(closed.isEmpty());
    }

    @Test void newSourceRecordsThePacerAndStoppedClearsIt() {
        assertNull(service.currentPacer());
        assertNotNull(service.newSource(config, roster, names));
        assertNotNull(service.currentPacer());
        service.stopped();
        assertNull(service.currentPacer());
    }

    @Test void sourceFailedToStartClearsThePacer() {
        assertNotNull(service.newSource(config, roster, names));
        service.sourceFailedToStart();
        assertNull(service.currentPacer());
    }

    @Test void newSourceReturnsNullWhenTheSourceCannotBeBuilt() {
        config.setYouTubeHandle("");
        assertNull(service.newSource(config, roster, names));
        assertNull(service.currentPacer());
    }

    @Test void lookupChargesOneUnitToTheRunningPacer() throws Exception {
        ChatSource source = service.newSource(config, roster, names);
        assertNotNull(source);
        QuotaPacer pacer = service.currentPacer();
        assertEquals("UC_viewer", service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertEquals(Endpoint.CHANNELS.cost(), pacer.usedToday());
    }

    @Test void lookupChargesOneUnitToTheSavedUsageWhenNoPacerRuns() throws Exception {
        assertEquals("UC_viewer", service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertEquals(LocalDate.now(QuotaPacer.QUOTA_ZONE).toString(), config.getYouTubeQuotaDay());
        assertEquals(Endpoint.CHANNELS.cost(), config.getYouTubeQuotaUsed());
        assertEquals(1, saves.get());
    }

    @Test void lookupWithoutAKeyFailsWithNoYouTubeKey() {
        config.setYouTubeApiKey("");
        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertInstanceOf(NoYouTubeKey.class, failure.getCause());
        assertTrue(created.isEmpty());
    }

    @Test void lookupClosesItsClientWhenYouTubeIsOffAndNothingElseUsesIt() throws Exception {
        config.setYouTubeEnabled(false);
        assertEquals("UC_viewer", service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertSame(created.get(0), closed.get(0).client());
    }

    @Test void queuedLookupFailsWithLookupUnavailableAfterShutdownLookups() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        lookups.execute(() -> {
            running.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        CompletableFuture<String> queued = service.lookUpViewerChannelId("@viewer");
        service.shutdownLookups();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS));
        assertInstanceOf(LookupUnavailable.class, failure.getCause());

        ExecutionException rejected = assertThrows(ExecutionException.class,
            () -> service.lookUpViewerChannelId("@other").get(5, TimeUnit.SECONDS));
        assertInstanceOf(LookupUnavailable.class, rejected.getCause());
        assertTrue(created.isEmpty(), "the queued lookup never ran");
    }

    @Test void closeClosesTheClientOffThreadAndWaitsForTheLookupThread() throws Exception {
        assertNotNull(service.newSource(config, roster, names));
        CountDownLatch running = new CountDownLatch(1);
        lookups.execute(() -> {
            running.countDown();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // a lookup still finishing its call: shutdown's interrupt does not cut it short
                }
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        service.shutdownLookups();
        service.close();
        assertTrue(lookups.isTerminated(), "close() waited for the lookup thread");
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), closed.get(0).thread());
    }

    @Test void lookupKeepsTheRunningSourcesClientWhenYouTubeIsTurnedOff() throws Exception {
        assertNotNull(service.newSource(config, roster, names));
        config.setYouTubeEnabled(false);
        assertEquals("UC_viewer", service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertFalse(closeLatch.await(200, TimeUnit.MILLISECONDS), "the running source still uses the client");
        assertTrue(closed.isEmpty());
    }

    @Test void lookupKeepsTheRunningSourcesClientWhenTheKeyChanges() throws Exception {
        assertNotNull(service.newSource(config, roster, names));
        config.setYouTubeApiKey("KEY_TWO");
        assertEquals("UC_viewer", service.lookUpViewerChannelId("@viewer").get(5, TimeUnit.SECONDS));
        assertEquals(1, created.size(), "no new client until the source restarts");
        assertFalse(closeLatch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(closed.isEmpty());
    }

    @Test void stoppedDetachesThePacersUsageListener() {
        assertNotNull(service.newSource(config, roster, names));
        QuotaPacer pacer = service.currentPacer();
        service.stopped();
        int savesAfterStop = saves.get();
        int usedAfterStop = config.getYouTubeQuotaUsed();
        pacer.recordCall(1);
        assertEquals(savesAfterStop, saves.get());
        assertEquals(usedAfterStop, config.getYouTubeQuotaUsed(), "a late call no longer reaches the saved usage");
    }
}
