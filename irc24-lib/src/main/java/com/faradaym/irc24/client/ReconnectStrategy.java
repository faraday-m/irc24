package com.faradaym.irc24.client;

import java.time.Duration;

/**
 * Determines delay before each reconnect attempt.
 * Return null to stop reconnecting.
 */
@FunctionalInterface
public interface ReconnectStrategy {

    Duration nextDelay(int attempt);

    /** 1s, 2s, 4s, 8s, ... capped at 30s */
    static ReconnectStrategy exponentialBackoff() {
        return attempt -> Duration.ofMillis(
                Math.min(1000L * (1L << attempt), 30_000L)
        );
    }

    /** Fixed delay — useful for tests */
    static ReconnectStrategy fixed(Duration delay) {
        return attempt -> delay;
    }

    /** Never reconnect */
    static ReconnectStrategy noReconnect() {
        return attempt -> null;
    }
}
