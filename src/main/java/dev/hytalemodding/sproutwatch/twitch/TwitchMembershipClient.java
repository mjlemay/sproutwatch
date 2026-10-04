package dev.hytalemodding.sproutwatch.twitch;

import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anonymous read-only Twitch chat client that keeps a ChatRoster current. One daemon thread;
 * reconnects with exponential backoff (initial delay doubling to 5 min, reset once a connection
 * survives 60 s). Requests twitch.tv/membership so JOIN/PART/NAMES arrive; if Twitch never ACKs
 * it, only chatters who speak will ever appear, which is warned once per connection.
 * One-shot: construct a new client per start; stop() is terminal.
 * Forked from Subinator's TwitchChatClient (same transport, proven on 0.6.3).
 */
public final class TwitchMembershipClient implements ChatSource {

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long READ_TIMEOUT_MS = 600_000; // Twitch pings ~every 5 min
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long HEALTHY_CONNECTION_MS = 60_000;

    private final String channel;
    private final ChatRoster roster;
    private final Logger logger;
    private final SocketFactory socketFactory;
    private final String host;
    private final int port;
    private final long initialBackoffMs;
    private final AtomicLong eventCount = new AtomicLong();

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Socket socket;
    private volatile String state = "stopped";
    /** Called on every state change (any thread); the plugin uses it to refresh open settings pages. */
    private volatile Runnable onStateChange;
    private volatile boolean membershipAcked;
    private boolean started;

    @Override
    public void setOnStateChange(Runnable hook) {
        onStateChange = hook;
    }

    private void setState(String next) {
        if (next.equals(state)) return;
        state = next;
        Runnable hook = onStateChange;
        if (hook == null) return;
        try {
            hook.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Sproutwatch state-change hook failed", e);
        }
    }

    /** Production client: TLS to irc.chat.twitch.tv:6697. */
    public TwitchMembershipClient(String channel, ChatRoster roster, Logger logger) {
        this(channel, roster, logger, SSLSocketFactory.getDefault(), "irc.chat.twitch.tv", 6697, 5_000);
    }

    TwitchMembershipClient(String channel, ChatRoster roster, Logger logger,
                           SocketFactory socketFactory, String host, int port, long initialBackoffMs) {
        this.channel = SproutwatchConfig.normalizeChannel(channel);
        this.roster = roster;
        this.logger = logger;
        this.socketFactory = socketFactory;
        this.host = host;
        this.port = port;
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("TwitchMembershipClient is one-shot; construct a new instance");
        }
        started = true;
        running = true;
        setState("connecting");
        thread = new Thread(this::runLoop, "sproutwatch-twitch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        setState("stopped");
        closeSocket();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    @Override
    public boolean isRunning() { return running; }
    @Override
    public String getState() { return state; }
    public String getChannel() { return channel; }
    public long getEventCount() { return eventCount.get(); }
    public boolean isMembershipAcked() { return membershipAcked; }
    public int getRosterSize() { return roster.size(); }

    private void runLoop() {
        long backoffMs = initialBackoffMs;
        try {
            while (running) {
                long startedAt = System.currentTimeMillis();
                try {
                    connectAndRead();
                } catch (IOException | RuntimeException e) {
                    if (!running) break;
                    setState("reconnecting");
                    logger.warning("Twitch connection lost (" + e.getMessage() + "); retrying in " + (backoffMs / 1000) + "s");
                }
                if (!running) break;
                long connectedMs = System.currentTimeMillis() - startedAt;
                backoffMs = connectedMs >= HEALTHY_CONNECTION_MS
                        ? initialBackoffMs
                        : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (running) {
                logger.severe("Twitch listener thread died unexpectedly - run /sproutwatch stop then start");
                running = false;
            }
            setState("stopped");
        }
    }

    private void connectAndRead() throws IOException {
        Socket s = socketFactory.createSocket();
        try {
            s.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT_MS);
            socket = s;
            s.setSoTimeout((int) READ_TIMEOUT_MS);
            if (!running) return;

            membershipAcked = false; // per connection
            boolean warnedNoMembership = false;
            int preAckLines = 0;
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

                out.println("CAP REQ :twitch.tv/membership twitch.tv/commands");
                out.println("NICK justinfan" + ThreadLocalRandom.current().nextInt(10_000, 100_000));
                out.println("JOIN #" + channel);
                setState("connected to #" + channel);
                logger.info("Sproutwatch watching Twitch channel #" + channel);

                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("PING")) {
                        out.println("PONG" + line.substring(4));
                        continue;
                    }
                    if (!membershipAcked) {
                        if (line.contains(" CAP ") && line.contains(" ACK ") && line.contains("twitch.tv/membership")) {
                            membershipAcked = true;
                            continue;
                        }
                        preAckLines++;
                        if (!warnedNoMembership && (line.contains(" 376 ") || preAckLines >= 50)) {
                            warnedNoMembership = true;
                            logger.warning("Twitch has not acknowledged the membership capability - JOIN/PART are missing, so only viewers who chat will get a sprout");
                        }
                    }
                    RosterEvent event = MembershipParser.parse(line);
                    if (event == null) continue;
                    eventCount.incrementAndGet();
                    try {
                        roster.apply(event, System.currentTimeMillis());
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "Roster update failed", e);
                    }
                }
            }
        } finally {
            if (socket == s) socket = null;
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
