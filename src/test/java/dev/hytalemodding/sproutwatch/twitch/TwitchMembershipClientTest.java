package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.SocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TwitchMembershipClientTest {

    private static final String CAP_ACK = ":tmi.twitch.tv CAP * ACK :twitch.tv/membership twitch.tv/commands";

    private static Logger warningsInto(String name, List<String> sink) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) sink.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    @Test
    @Timeout(15)
    void handshakesAppliesRosterEventsAndPongs() throws Exception {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        CompletableFuture<List<String>> received = new CompletableFuture<>();
        CompletableFuture<String> pong = new CompletableFuture<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = warningsInto("tmc-1", warnings);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread mock = new Thread(() -> {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    received.complete(List.of(in.readLine(), in.readLine(), in.readLine()));
                    out.println(CAP_ACK);
                    out.println(":justinfan1.tmi.twitch.tv 353 justinfan1 = #streamer :alice bob");
                    out.println(":carol!carol@carol.tmi.twitch.tv JOIN #streamer");
                    out.println(":dave!dave@dave.tmi.twitch.tv PRIVMSG #streamer :hi");
                    out.println(":bob!bob@bob.tmi.twitch.tv PART #streamer");
                    out.println("PING :tmi.twitch.tv");
                    pong.complete(in.readLine());
                } catch (IOException e) {
                    received.completeExceptionally(e);
                    pong.completeExceptionally(e);
                }
            });
            mock.start();

            TwitchMembershipClient client = new TwitchMembershipClient(
                "streamer", roster, logger, SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 5_000);
            List<String> states = new CopyOnWriteArrayList<>();
            client.setOnStateChange(() -> states.add(client.getState()));
            client.start();
            try {
                List<String> hs = received.get(10, TimeUnit.SECONDS);
                assertEquals("CAP REQ :twitch.tv/membership twitch.tv/commands", hs.get(0));
                assertTrue(hs.get(1).startsWith("NICK justinfan"));
                assertEquals("JOIN #streamer", hs.get(2));
                // The PONG is written only after every earlier line was read and applied.
                assertEquals("PONG :tmi.twitch.tv", pong.get(10, TimeUnit.SECONDS));
                assertEquals(Set.of("alice", "carol", "dave"), roster.snapshot().keySet());
                assertTrue(client.isMembershipAcked());
                assertEquals(List.of("connecting", "connected to #streamer"), states.subList(0, 2),
                    "the hook fires on each state change so open pages refresh at once");
                assertEquals(0, warnings.stream().filter(w -> w != null && w.contains("membership")).count(),
                    "no membership warning when ACK arrives, got: " + warnings);
            } finally {
                client.stop();
                mock.join(5_000);
            }
        }
    }

    @Test
    @Timeout(15)
    void warnsOnceWhenMembershipNeverAcked() throws Exception {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        CompletableFuture<String> pong = new CompletableFuture<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = warningsInto("tmc-2", warnings);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread mock = new Thread(() -> {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    in.readLine(); in.readLine(); in.readLine();
                    out.println(":tmi.twitch.tv 001 justinfan1 :Welcome");
                    out.println(":tmi.twitch.tv 376 justinfan1 :End of /MOTD"); // warning trigger
                    out.println(":erin!erin@erin.tmi.twitch.tv PRIVMSG #streamer :still here");
                    out.println("PING :tmi.twitch.tv");
                    pong.complete(in.readLine());
                } catch (IOException e) {
                    pong.completeExceptionally(e);
                }
            });
            mock.start();

            TwitchMembershipClient client = new TwitchMembershipClient(
                "streamer", roster, logger, SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 5_000);
            client.start();
            try {
                assertEquals("PONG :tmi.twitch.tv", pong.get(10, TimeUnit.SECONDS));
                assertFalse(client.isMembershipAcked());
                assertEquals(1, warnings.stream().filter(w -> w != null && w.contains("membership")).count(),
                    "exactly one membership warning, got: " + warnings);
                assertEquals(Set.of("erin"), roster.snapshot().keySet(), "speakers still count without membership");
            } finally {
                client.stop();
                mock.join(5_000);
            }
        }
    }

    @Test
    @Timeout(20)
    void reconnectWarningIsFullyFormatted() throws Exception {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = warningsInto("tmc-4", warnings);

        try (ServerSocket server = new ServerSocket(0)) {
            // Accept every (re)connect until the ServerSocket is closed: read the handshake, then
            // drop the connection with a TCP reset so the client's read fails and it reconnects.
            Thread mock = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                        in.readLine(); in.readLine(); in.readLine();
                        s.setSoLinger(true, 0);
                    } catch (IOException e) {
                        return; // server closed
                    }
                }
            });
            mock.setDaemon(true);
            mock.start();

            TwitchMembershipClient client = new TwitchMembershipClient(
                "streamer", roster, logger, SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 50);
            client.start();
            try {
                long deadline = System.currentTimeMillis() + 10_000;
                String warning = null;
                while (warning == null && System.currentTimeMillis() < deadline) {
                    warning = warnings.stream().filter(w -> w != null && w.contains("Twitch connection lost")).findFirst().orElse(null);
                    if (warning == null) Thread.sleep(20);
                }
                assertNotNull(warning, "expected a reconnect warning, got: " + warnings);
                assertTrue(warning.contains("retrying in"), warning);
                assertFalse(warning.contains("{"), "message must be pre-formatted (the server backend prints it raw): " + warning);
            } finally {
                client.stop();
            }
        }
    }

    @Test
    void isOneShot() {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        TwitchMembershipClient client = new TwitchMembershipClient(
            "streamer", roster, Logger.getLogger("tmc-3"), SocketFactory.getDefault(), "127.0.0.1", 1, 5_000);
        client.start();
        client.stop();
        assertThrows(IllegalStateException.class, client::start);
        assertEquals("stopped", client.getState());
    }
}
