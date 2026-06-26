package com.faradaym.irc24.client;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.Optional;

/**
 * Immutable client configuration.
 * Use {@link #of} for sensible defaults, then rebuild with with* methods.
 * IrcClient holds this in an AtomicReference — swapping via updateConfig() is atomic,
 * so the next reconnect picks up the new host/port automatically.
 */
public record IrcClientConfig(
        String host,
        int port,
        String nick,
        String user,
        String realName,
        Optional<String> password,      // PASS command before NICK/USER; empty = no password
        boolean tls,                    // enable SSL/TLS; default false
        Optional<SSLContext> sslContext, // custom SSLContext; empty = JVM default when tls=true
        int circuitBreakerThreshold,    // default: 3
        Duration connectTimeout,        // default: 30s
        Duration handshakeTimeout,      // wait for 001 RPL_WELCOME; default: 10s
        ReconnectStrategy reconnect,    // default: exponential backoff
        boolean autoRejoin,             // default: false
        String ctcpVersion              // reply to CTCP VERSION; default: "irc24"
) {
    public static IrcClientConfig of(String host, int port, String nick) {
        return new IrcClientConfig(
                host, port,
                nick, toAsciiUser(nick), nick,
                Optional.empty(),
                false,
                Optional.empty(),
                3,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                ReconnectStrategy.exponentialBackoff(),
                false,
                "irc24"
        );
    }

    /** Strips non-ASCII chars from nick to produce a valid IRC username. Falls back to "user" if empty. */
    private static String toAsciiUser(String nick) {
        String ascii = nick.toLowerCase().replaceAll("[^a-z0-9._-]", "").replaceFirst("^[^a-z]+", "");
        return ascii.isEmpty() ? "user" : ascii;
    }

    /** For tests — no nick required (loopback FakeServer handles handshake) */
    public static IrcClientConfig of(String host, int port) {
        return of(host, port, "testuser");
    }

    public IrcClientConfig withNick(String nick) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withPassword(String password) {
        return new IrcClientConfig(host, port, nick, user, realName, Optional.of(password), tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withTls(boolean tls) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, ctcpVersion);
    }

    /** Sets a custom SSLContext and enables TLS automatically. */
    public IrcClientConfig withSslContext(SSLContext ctx) {
        return new IrcClientConfig(host, port, nick, user, realName, password, true, Optional.of(ctx),
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withReconnect(ReconnectStrategy strategy) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, strategy, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withAutoRejoin(boolean autoRejoin) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withHandshakeTimeout(Duration timeout) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, timeout, reconnect, autoRejoin, ctcpVersion);
    }

    public IrcClientConfig withCtcpVersion(String version) {
        return new IrcClientConfig(host, port, nick, user, realName, password, tls, sslContext,
                circuitBreakerThreshold, connectTimeout, handshakeTimeout, reconnect, autoRejoin, version);
    }
}
