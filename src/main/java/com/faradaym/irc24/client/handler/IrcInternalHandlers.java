package com.faradaym.irc24.client.handler;

import com.faradaym.irc24.parser.IrcMessage;
import com.faradaym.irc24.protocol.IrcCommand;
import com.faradaym.irc24.protocol.IrcMessages;
import com.faradaym.irc24.protocol.IrcReply;
import com.faradaym.irc24.client.IrcClientConfig;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Builds the Map of internal IRC message handlers.
 *
 * Used as a builder: set dependencies via fluent methods,
 * then call {@link #build()} to get the ready Map.
 *
 * <pre>{@code
 * Map<String, InternalHandler> handlers = new IrcInternalHandlers()
 *     .writer(line -> connection.writeLine(line))
 *     .config(config)
 *     .latch(() -> welcomeLatch)
 *     .joinedChannels(joinedChannels)
 *     .pendingNames(pendingNames)
 *     .build();
 * }</pre>
 */
public class IrcInternalHandlers {

    /**
     * Writes a single wire-format line to the connection.
     * IrcClient supplies: {@code line -> connection.writeLine(line)} —
     * always reads the current volatile connection, safe after reconnect.
     */
    @FunctionalInterface
    public interface LineWriter {
        void writeLine(String line) throws IOException;
    }

    private LineWriter writer;
    private AtomicReference<IrcClientConfig> config;
    private Supplier<CountDownLatch> latch;
    private Set<String> joinedChannels;
    private ConcurrentHashMap<String, PendingNames> pendingNames;

    // -----------------------------------------------------------------------
    // Builder-style setters
    // -----------------------------------------------------------------------

    public IrcInternalHandlers writer(LineWriter writer) {
        this.writer = writer;
        return this;
    }

    public IrcInternalHandlers config(AtomicReference<IrcClientConfig> config) {
        this.config = config;
        return this;
    }

    public IrcInternalHandlers latch(Supplier<CountDownLatch> latch) {
        this.latch = latch;
        return this;
    }

    public IrcInternalHandlers joinedChannels(Set<String> joinedChannels) {
        this.joinedChannels = joinedChannels;
        return this;
    }

    public IrcInternalHandlers pendingNames(ConcurrentHashMap<String, PendingNames> pendingNames) {
        this.pendingNames = pendingNames;
        return this;
    }

    /** Builds the Map: command/code → handler. */
    public Map<String, InternalHandler> build() {
        return Map.of(
                IrcCommand.PING,         this::onPing,
                IrcReply.RPL_WELCOME,    this::onWelcome,
                IrcCommand.PRIVMSG,      this::onPrivmsg,
                IrcCommand.JOIN,         this::onJoin,
                IrcCommand.PART,         this::onPart,
                IrcReply.RPL_NAMREPLY,   this::onNamreply,
                IrcReply.RPL_ENDOFNAMES, this::onEndOfNames
        );
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private boolean onPing(IrcMessage msg) throws IOException {
        String server = msg.trailing() != null ? msg.trailing()
                : msg.params().isEmpty() ? "server"
                : msg.params().getFirst();
        writer.writeLine(IrcMessages.pong(server));
        return true; // consumed — PING is not forwarded to user handlers
    }

    private boolean onWelcome(IrcMessage msg) {
        CountDownLatch l = latch.get();
        if (l != null) l.countDown();
        return false; // forwarded — user handlers may process 001
    }

    private boolean onPrivmsg(IrcMessage msg) throws IOException {
        if (msg.trailing() != null
                && isCtcp(msg.trailing(), "VERSION")
                && msg.prefix() != null) {
            writer.writeLine(IrcMessages.ctcpReply(
                    nickFromPrefix(msg.prefix()), "VERSION", config.get().ctcpVersion()));
            return true; // CTCP VERSION consumed
        }
        return false;
    }

    private boolean onJoin(IrcMessage msg) {
        String channel = !msg.params().isEmpty() ? msg.params().getFirst() : msg.trailing();
        if (channel != null) joinedChannels.add(channel);
        return false;
    }

    private boolean onPart(IrcMessage msg) {
        String channel = !msg.params().isEmpty() ? msg.params().getFirst() : msg.trailing();
        if (channel != null) joinedChannels.remove(channel);
        return false;
    }

    private boolean onNamreply(IrcMessage msg) {
        if (msg.params().size() >= 2 && msg.trailing() != null) {
            String channel = msg.params().getLast().toLowerCase();
            PendingNames pending = pendingNames.get(channel);
            if (pending != null) {
                for (String raw : msg.trailing().split(" ")) {
                    String nick = raw.replaceAll("^[@+%&~!]+", "");
                    if (!nick.isEmpty()) pending.users().add(nick);
                }
            }
        }
        return false;
    }

    private boolean onEndOfNames(IrcMessage msg) {
        if (msg.params().size() >= 2) {
            String channel = msg.params().get(1).toLowerCase();
            PendingNames pending = pendingNames.remove(channel);
            if (pending != null) {
                pending.future().complete(List.copyOf(pending.users()));
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Utils
    // -----------------------------------------------------------------------

    private static boolean isCtcp(String trailing, String type) {
        char ctcp = IrcMessages.CTCP;
        return trailing.length() > 2
                && trailing.charAt(0) == ctcp
                && trailing.charAt(trailing.length() - 1) == ctcp
                && trailing.substring(1, trailing.length() - 1).equals(type);
    }

    private static String nickFromPrefix(String prefix) {
        int bang = prefix.indexOf('!');
        return bang >= 0 ? prefix.substring(0, bang) : prefix;
    }
}
