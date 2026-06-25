package com.faradaym.irc24.client;

import com.faradaym.irc24.client.handler.CircuitBreaker;
import com.faradaym.irc24.client.handler.InternalHandler;
import com.faradaym.irc24.client.handler.IrcEventHandler;
import com.faradaym.irc24.client.handler.IrcInternalHandlers;
import com.faradaym.irc24.client.handler.PendingNames;
import com.faradaym.irc24.connection.IrcConnection;
import com.faradaym.irc24.parser.IrcMessage;
import com.faradaym.irc24.parser.IrcMessageParser;
import com.faradaym.irc24.protocol.IrcMessages;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-level IRC client.
 *
 * Responsible for: connection lifecycle, reconnect, dispatching incoming messages,
 * and managing handlers. Commands are delegated to {@link IrcCommandSender}.
 */
public class IrcClient implements Closeable {

    private static final AtomicLong CLIENT_ID = new AtomicLong();

    private static final IrcMessage POISON =
            new IrcMessage(Map.of(), null, "__POISON__", List.of(), null);

    private final AtomicReference<IrcClientConfig> config;
    private final IrcMessageParser parser = new IrcMessageParser();
    private final List<HandlerEntry> handlers = new CopyOnWriteArrayList<>();

    private final Set<String> joinedChannels =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ConcurrentHashMap<String, PendingNames> pendingNames =
            new ConcurrentHashMap<>();

    /** IRC commands — writes via lambda to the current volatile connection */
    private final IrcCommandSender sender;

    /** Command → internal handler; built once in the constructor */
    private final Map<String, InternalHandler> internalHandlers;

    private volatile CountDownLatch welcomeLatch;
    private volatile IrcConnection connection;
    private volatile boolean running;
    /** false between a connection drop and a successful reconnect — handlers are paused */
    private volatile boolean dispatching = true;

    private record HandlerEntry(
            IrcEventHandler handler,
            CircuitBreaker breaker,
            BlockingQueue<IrcMessage> queue,
            Thread worker
    ) {}

    public IrcClient(IrcClientConfig config) {
        this.config = new AtomicReference<>(config);
        // Lambda reads this.connection on every call — always current after reconnect
        this.sender = new IrcCommandSender(line -> connection.writeLine(line));
        this.internalHandlers = new IrcInternalHandlers()
                .writer(line -> connection.writeLine(line))
                .config(this.config)
                .latch(() -> welcomeLatch)
                .joinedChannels(joinedChannels)
                .pendingNames(pendingNames)
                .build();
    }

    public void updateConfig(IrcClientConfig newConfig) {
        config.set(newConfig);
    }

    public void addHandler(IrcEventHandler handler) {
        int threshold = config.get().circuitBreakerThreshold();
        BlockingQueue<IrcMessage> queue = new LinkedBlockingQueue<>();
        CircuitBreaker breaker = new CircuitBreaker(threshold);

        Thread worker = Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    IrcMessage msg = queue.take();
                    if (msg == POISON) break;
                    if (breaker.isOpen()) continue;
                    try {
                        handler.handle(msg);
                        breaker.recordSuccess();
                    } catch (Exception e) {
                        breaker.recordFailure();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        handlers.add(new HandlerEntry(handler, breaker, queue, worker));
    }

    /**
     * Connects, performs the IRC handshake (PASS/NICK/USER), and waits for 001 RPL_WELCOME.
     * Throws IOException if 001 is not received within handshakeTimeout.
     */
    public void start() throws IOException {
        connection = connect();
        running = true;
        sendHandshake(config.get());

        welcomeLatch = new CountDownLatch(1);
        Thread.ofVirtual()
                .name("irc-reader-" + CLIENT_ID.getAndIncrement())
                .start(this::readLoop);

        try {
            boolean welcomed = welcomeLatch.await(
                    config.get().handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!welcomed) {
                close();
                throw new IOException(
                        "IRC handshake timeout: server did not send 001 within " + config.get().handshakeTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Interrupted during IRC handshake", e);
        }
    }

    /** IRC session commands — sendMessage, join, op, kick, etc. */
    public IrcSession commands() { return sender; }

    // -----------------------------------------------------------------------
    // Async query — stateful, not part of IrcSession
    // -----------------------------------------------------------------------

    /**
     * Requests the channel user list via NAMES.
     * Returns a CompletableFuture that completes when 366 (RPL_ENDOFNAMES) is received.
     * Nicks are returned without mode prefixes (@, +, %, etc.).
     */
    public CompletableFuture<List<String>> getUsers(String channel) throws IOException {
        String key = channel.toLowerCase();
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        PendingNames old = pendingNames.put(key, new PendingNames(new ArrayList<>(), future));
        if (old != null) {
            old.future().completeExceptionally(
                    new IllegalStateException("Superseded by a new NAMES request"));
        }
        connection.writeLine(IrcMessages.names(channel));
        return future;
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    private void handleInternal(IrcMessage msg) throws IOException {
        InternalHandler h = internalHandlers.get(msg.command());
        boolean consumed = h != null && h.handle(msg);
        if (!consumed) dispatch(msg);
    }

    private void dispatch(IrcMessage msg) {
        if (!dispatching) return;
        for (HandlerEntry entry : handlers) {
            entry.queue().offer(msg);
        }
    }

    // -----------------------------------------------------------------------
    // Connection management
    // -----------------------------------------------------------------------

    private void readLoop() {
        outer:
        while (running) {
            try {
                String raw;
                while (running && (raw = connection.readLine()) != null) {
                    handleInternal(parser.parse(raw));
                }
            } catch (IOException e) {
                // fall through to reconnect
            }
            if (!running) break;
            dispatching = false; // pause handlers while reconnecting
            if (!reconnect()) break outer;
        }
    }

    private boolean reconnect() {
        ReconnectStrategy strategy = config.get().reconnect();
        int attempt = 0;
        while (running) {
            Duration delay = strategy.nextDelay(attempt++);
            if (delay == null) return false;
            try {
                Thread.sleep(delay);
                connection = connect();
                IrcClientConfig cfg = config.get();
                sendHandshake(cfg);
                if (cfg.autoRejoin()) {
                    for (String channel : joinedChannels) {
                        connection.writeLine(IrcMessages.join(channel));
                    }
                }
                dispatching = true; // connection restored — handlers are live again
                return true;
            } catch (IOException e) {
                // retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void sendHandshake(IrcClientConfig cfg) throws IOException {
        if (cfg.password().isPresent()) {
            connection.writeLine(IrcMessages.pass(cfg.password().get()));
        }
        connection.writeLine(IrcMessages.nick(cfg.nick()));
        connection.writeLine(IrcMessages.user(cfg.user(), cfg.realName()));
    }

    private IrcConnection connect() throws IOException {
        IrcClientConfig cfg = config.get();
        return new IrcConnection(cfg.host(), cfg.port(), cfg.connectTimeout(), cfg.tls(),
                cfg.sslContext().orElse(null));
    }

    @Override
    public void close() throws IOException {
        running = false;
        handlers.forEach(e -> e.queue().offer(POISON));
        IrcConnection conn = connection;
        if (conn != null) conn.close();
    }
}
