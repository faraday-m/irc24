package com.faradaym.irc24.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;



class ReconnectTest {

    private IrcClientConfig fastReconnect(FakeServer server) {
        return IrcClientConfig.of("localhost", server.port())
                .withReconnect(ReconnectStrategy.fixed(Duration.ofMillis(100)));
    }

    // --- Reconnect after disconnect ---

    @Test
    void reconnectsAfterServerDisconnect() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(fastReconnect(server));

        CountDownLatch afterReconnect = new CountDownLatch(1);
        client.addHandler(msg -> afterReconnect.countDown());

        // drop the connection
        server.disconnect();

        // wait until client reconnects and accept completes
        CountDownLatch reconnected = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            server.awaitConnectionAndWelcomeQuietly("testnick");
            reconnected.countDown();
        });
        assertTrue(reconnected.await(3, TimeUnit.SECONDS), "Client should reconnect");

        server.send(":n!u@h PRIVMSG #c :after reconnect");
        assertTrue(afterReconnect.await(2, TimeUnit.SECONDS),
                "Handler should fire after reconnect");

        client.close();
        server.close();
    }

    // --- noReconnect — stays disconnected ---

    @Test
    void doesNotReconnectWhenStrategyIsNoReconnect() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(
                IrcClientConfig.of("localhost", server.port())
                        .withReconnect(ReconnectStrategy.noReconnect())
        );

        server.disconnect();

        // Wait a bit — client must not attempt to reconnect
        CountDownLatch shouldNotFire = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            server.awaitConnectionQuietly(); // fires if client reconnects
            shouldNotFire.countDown();
        });

        assertFalse(shouldNotFire.await(500, TimeUnit.MILLISECONDS),
                "Client must not reconnect when strategy is noReconnect");

        client.close();
        server.close();
    }

    // --- Runtime server switch ---

    @Test
    void reconnectsToNewServerAfterConfigUpdate() throws Exception {
        FakeServer server1 = new FakeServer();
        FakeServer server2 = new FakeServer();

        IrcClient client = server1.connectClient(fastReconnect(server1));

        CountDownLatch onServer2 = new CountDownLatch(1);
        client.addHandler(msg -> {
            if ("from-server2".equals(msg.trailing())) onServer2.countDown();
        });

        // switch config to server2 before disconnecting
        client.updateConfig(IrcClientConfig.of("localhost", server2.port())
                .withReconnect(ReconnectStrategy.fixed(Duration.ofMillis(100))));

        server1.disconnect();

        // wait for connection to server2
        CountDownLatch reconnected = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            server2.awaitConnectionAndWelcomeQuietly("testnick");
            reconnected.countDown();
        });
        assertTrue(reconnected.await(3, TimeUnit.SECONDS), "Client should connect to server2");

        server2.send(":n!u@h PRIVMSG #c :from-server2");
        assertTrue(onServer2.await(2, TimeUnit.SECONDS),
                "Client should connect to the new server");

        client.close();
        server1.close();
        server2.close();
    }

    // --- AutoRejoin ---

    @Test
    void autoRejoinSendsJoinAfterReconnect() throws Exception {
        FakeServer server = new FakeServer();
        IrcClientConfig config = IrcClientConfig.of("localhost", server.port())
                .withReconnect(ReconnectStrategy.fixed(Duration.ofMillis(100)))
                .withAutoRejoin(true);

        IrcClient client = server.connectClient(config);

        // Server sends JOIN — client records the channel
        server.send(":nick!u@h JOIN #general");
        Thread.sleep(100); // give time to process

        // Disconnect + wait for reconnect
        server.disconnect();
        CountDownLatch reconnected = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            // awaitConnectionAndWelcomeQuietly drains NICK/USER — next readLine() gets JOIN
            server.awaitConnectionAndWelcomeQuietly("testnick");
            reconnected.countDown();
        });
        assertTrue(reconnected.await(3, TimeUnit.SECONDS));

        // Client must automatically send JOIN #general
        assertEquals("JOIN #general", server.readLine());

        client.close();
        server.close();
    }
}
