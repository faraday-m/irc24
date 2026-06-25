package com.faradaym.irc24.client;

import com.faradaym.irc24.client.handler.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    // --- Basic behaviour ---

    @Test
    void startsClosedAllowingCalls() {
        CircuitBreaker cb = new CircuitBreaker(3);
        assertFalse(cb.isOpen());
    }

    @Test
    void remainsClosedBelowThreshold() {
        CircuitBreaker cb = new CircuitBreaker(3);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen());
    }

    @Test
    void opensAfterThresholdFailures() {
        CircuitBreaker cb = new CircuitBreaker(3);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertTrue(cb.isOpen());
    }

    // --- Reset on success ---

    @Test
    void successResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(3);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen());
    }

    @Test
    void doesNotReopenAfterSuccessResets() {
        CircuitBreaker cb = new CircuitBreaker(3);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        assertFalse(cb.isOpen());
    }

    // --- Integration with IrcClient dispatch ---

    private IrcClientConfig noReconnect(FakeServer server) {
        return IrcClientConfig.of("localhost", server.port())
                .withReconnect(ReconnectStrategy.noReconnect());
    }

    @Test
    void openCircuitBreakerPreventsHandlerFromBeingCalled() throws Exception {
        FakeServer server = new FakeServer();
        AtomicInteger callCount = new AtomicInteger(0);

        IrcClient client = server.connectClient(noReconnect(server));
        client.addHandler(msg -> {
            callCount.incrementAndGet();
            throw new RuntimeException("always fails");
        });

        server.send(":n!u@h PRIVMSG #c :one");
        server.send(":n!u@h PRIVMSG #c :two");
        server.send(":n!u@h PRIVMSG #c :three");
        Thread.sleep(300);

        assertEquals(3, callCount.get(), "Handler should have been called exactly 3 times");

        server.send(":n!u@h PRIVMSG #c :four");
        Thread.sleep(300);

        assertEquals(3, callCount.get(), "Handler must not be called after the circuit breaker opens");

        client.close();
        server.close();
    }

    @Test
    void circuitBreakerDoesNotAffectOtherHandlers() throws Exception {
        FakeServer server = new FakeServer();
        AtomicInteger goodHandlerCount = new AtomicInteger(0);

        IrcClient client = server.connectClient(noReconnect(server));
        client.addHandler(msg -> { throw new RuntimeException("broken"); });
        client.addHandler(msg -> goodHandlerCount.incrementAndGet());

        server.send(":n!u@h PRIVMSG #c :one");
        server.send(":n!u@h PRIVMSG #c :two");
        server.send(":n!u@h PRIVMSG #c :three");
        server.send(":n!u@h PRIVMSG #c :four");
        Thread.sleep(400);

        assertEquals(4, goodHandlerCount.get(), "Second handler must not be affected by the first handler's circuit breaker");

        client.close();
        server.close();
    }
}
