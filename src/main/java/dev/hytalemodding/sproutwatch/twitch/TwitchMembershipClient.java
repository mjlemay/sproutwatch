package dev.hytalemodding.sproutwatch.twitch;

import dev.hytalemodding.sproutwatch.chat.ChatSource;
import dev.hytalemodding.sproutwatch.chat.SourceLifecycle;
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
 * One-shot: construct a new client per start; stop() is terminal. The listener thread checks its
 * run is still live (see {@link SourceLifecycle}) before every state change and every roster
 * update, so a line read just before stop() never lands after it.
 * Plain IRC-over-TLS transport, verified on 0.6.3.
 */
public final class TwitchMembershipClient implements ChatSource {

    private static final long CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final long READ_TIMEOUT_MILLIS = 600_000; // Twitch pings ~every 5 min
    private static final long MAX_BACKOFF_MILLIS = 300_000;
    private static final long HEALTHY_CONNECTION_MILLIS = 60_000;

    private final String channel;
    private final ChatRoster roster;
    private final Logger logger;
    private final SocketFactory socketFactory;
    private final String host;
    private final int port;
    private final long initialBackoffMillis;
    private final AtomicLong eventCount = new AtomicLong();

    private final SourceLifecycle lifecycle;
    private volatile Thread thread;
    private volatile Socket socket;
    private volatile boolean membershipAcknowledged;
    private boolean started;

    /** Called on every state change (any thread); the plugin uses it to refresh open settings pages. */
    @Override
    public void setOnStateChange(Runnable hook) {
        lifecycle.setOnStateChange(hook);
    }

    /** Production client: TLS to irc.chat.twitch.tv:6697. */
    public TwitchMembershipClient(String channel, ChatRoster roster, Logger logger) {
        this(channel, roster, logger, SSLSocketFactory.getDefault(), "irc.chat.twitch.tv", 6697, 5_000);
    }

    TwitchMembershipClient(String channel, ChatRoster roster, Logger logger,
                           SocketFactory socketFactory, String host, int port, long initialBackoffMillis) {
        this.channel = SproutwatchConfig.normalizeChannel(channel);
        this.roster = roster;
        this.logger = logger;
        this.socketFactory = socketFactory;
        this.host = host;
        this.port = port;
        this.initialBackoffMillis = initialBackoffMillis;
        this.lifecycle = new SourceLifecycle(logger, null, "stopped");
    }

    @Override
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("TwitchMembershipClient is one-shot; construct a new instance");
        }
        started = true;
        long generation = lifecycle.begin("connecting");
        thread = new Thread(() -> runLoop(generation), "sproutwatch-twitch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        lifecycle.end("stopped", this::closeAndJoin);
    }

    private void closeAndJoin() {
        closeSocket();
        Thread worker = thread;
        thread = null;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(STOP_JOIN_MILLIS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() { return lifecycle.running(); }
    @Override
    public String getState() { return lifecycle.state(); }
    public String getChannel() { return channel; }
    public long getEventCount() { return eventCount.get(); }
    public boolean isMembershipAcknowledged() { return membershipAcknowledged; }
    public int getRosterSize() { return roster.size(); }

    private void runLoop(long generation) {
        long backoffMillis = initialBackoffMillis;
        try {
            while (lifecycle.live(generation)) {
                long startedAt = System.currentTimeMillis();
                try {
                    connectAndRead(generation);
                } catch (IOException | RuntimeException exception) {
                    if (!lifecycle.live(generation)) break;
                    lifecycle.setState(generation, "reconnecting");
                    logger.warning("Twitch connection lost (" + exception.getMessage() + "); retrying in " + (backoffMillis / 1000) + "s");
                }
                if (!lifecycle.live(generation)) break;
                long connectedMillis = System.currentTimeMillis() - startedAt;
                backoffMillis = connectedMillis >= HEALTHY_CONNECTION_MILLIS
                        ? initialBackoffMillis
                        : Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (lifecycle.finish(generation, "stopped")) {
                logger.severe("Twitch listener thread died unexpectedly - run /sproutwatch stop then start");
            }
        }
    }

    private void connectAndRead(long generation) throws IOException {
        Socket newSocket = socketFactory.createSocket();
        // Published before connecting, so a stop() during the connect timeout can close it. A stop()
        // that ran before this line is caught by the live check after connecting.
        socket = newSocket;
        try {
            newSocket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT_MILLIS);
            newSocket.setSoTimeout((int) READ_TIMEOUT_MILLIS);
            if (!lifecycle.live(generation)) return;

            membershipAcknowledged = false; // per connection
            boolean warnedNoMembership = false;
            int preAckLines = 0;
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                out.println("CAP REQ :twitch.tv/membership twitch.tv/commands");
                out.println("NICK justinfan" + ThreadLocalRandom.current().nextInt(10_000, 100_000));
                out.println("JOIN #" + channel);
                lifecycle.setState(generation, "connected to #" + channel);
                logger.info("Sproutwatch watching Twitch channel #" + channel);

                String line;
                while (lifecycle.live(generation) && (line = in.readLine()) != null) {
                    if (line.startsWith("PING")) {
                        out.println("PONG" + line.substring(4));
                        continue;
                    }
                    if (!membershipAcknowledged) {
                        if (line.contains(" CAP ") && line.contains(" ACK ") && line.contains("twitch.tv/membership")) {
                            membershipAcknowledged = true;
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
                    // The line may have been read (or held up above) after stop(): the plugin may
                    // already have cleared the roster.
                    if (!lifecycle.live(generation)) break;
                    eventCount.incrementAndGet();
                    try {
                        roster.apply(event, System.currentTimeMillis());
                    } catch (RuntimeException exception) {
                        logger.log(Level.WARNING, "Roster update failed", exception);
                    }
                }
            }
        } finally {
            if (socket == newSocket) socket = null;
            try { newSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
    }
}
