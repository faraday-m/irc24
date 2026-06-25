package com.faradaym.irc24.client.handler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-handler circuit breaker.
 * Opens after {@code threshold} consecutive failures, blocking further calls.
 * Resets on any successful invocation.
 */
public class CircuitBreaker {

    private final int threshold;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean open = false;

    public CircuitBreaker(int threshold) {
        this.threshold = threshold;
    }

    public boolean isOpen() {
        return open;
    }

    public void recordSuccess() {
        failureCount.set(0);
    }

    public void recordFailure() {
        if (failureCount.incrementAndGet() >= threshold) {
            open = true;
            // TODO: notify user (listener/callback)
        }
    }
}
