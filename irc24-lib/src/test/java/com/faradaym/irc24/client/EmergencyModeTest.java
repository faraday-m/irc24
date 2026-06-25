package com.faradaym.irc24.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyModeTest {

    private IrcClientConfig reconnectConfig(FakeServer server) {
        return IrcClientConfig.of("localhost", server.port(), "nick")
                // one attempt after 100ms, then give up
                .withReconnect(attempt -> attempt == 0 ? java.time.Duration.ofMillis(100) : null);
    }

    // --- Handlers are paused during reconnect ---

    @Test
    void handlersDoNotReceiveMessagesDuringReconnectPause() throws Exception {
        FakeServer server = new FakeServer();
        AtomicInteger count = new AtomicInteger(0);

        // Slow reconnect — 400ms pause between attempts
        IrcClientConfig config = IrcClientConfig.of("localhost", server.port(), "nick")
                .withReconnect(attempt -> attempt == 0 ? java.time.Duration.ofMillis(400) : null);

        IrcClient client = server.connectClient(config);
        client.addHandler(msg -> count.incrementAndGet());

        // Verify handler fires before disconnect
        server.send(":n!u@h PRIVMSG #c :before");
        Thread.sleep(150);
        assertEquals(1, count.get());

        // Disconnect → client enters reconnect pause (400ms)
        server.disconnect();
        Thread.sleep(200); // mid-pause — dispatching should be false

        // Nothing to send during the gap, but the counter must not grow
        assertEquals(1, count.get(), "Handler must not receive messages during reconnect pause");

        client.close();
        server.close();
    }

    // --- Handlers resume after reconnect ---

    @Test
    void handlersResumeAfterReconnect() throws Exception {
        FakeServer server = new FakeServer();
        CountDownLatch afterReconnect = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();

        IrcClient client = server.connectClient(reconnectConfig(server));
        client.addHandler(msg -> {
            received.add(msg.trailing());
            if ("after-reconnect".equals(msg.trailing())) afterReconnect.countDown();
        });

        // Message before disconnect
        server.send(":n!u@h PRIVMSG #c :before-disconnect");
        Thread.sleep(100);

        // Disconnect → trigger reconnect
        server.disconnect();
        Thread.ofVirtual().start(() -> server.awaitConnectionAndWelcomeQuietly("nick"));

        // Wait for reconnect (100ms delay + margin)
        Thread.sleep(300);

        // Message after reconnect — handler must receive it
        server.send(":n!u@h PRIVMSG #c :after-reconnect");
        assertTrue(afterReconnect.await(2, TimeUnit.SECONDS),
                "Handler must receive messages after connection is restored");

        client.close();
        server.close();
    }
}
