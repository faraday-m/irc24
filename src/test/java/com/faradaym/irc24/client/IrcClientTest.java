package com.faradaym.irc24.client;

import com.faradaym.irc24.parser.IrcMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IrcClientTest {

    private IrcClientConfig noReconnect(FakeServer server) {
        return IrcClientConfig.of("localhost", server.port())
                .withReconnect(ReconnectStrategy.noReconnect());
    }

    // --- Receiving a message ---

    @Test
    void dispatchesIncomingMessageToHandlers() throws Exception {
        FakeServer server = new FakeServer();
        CountDownLatch latch = new CountDownLatch(1);
        List<IrcMessage> received = new CopyOnWriteArrayList<>();

        IrcClient client = server.connectClient(noReconnect(server));
        client.addHandler(msg -> { received.add(msg); latch.countDown(); });

        server.send(":nick!u@h PRIVMSG #chan :Hello!");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("PRIVMSG", received.getFirst().command());
        assertEquals("Hello!", received.getFirst().trailing());

        client.close();
        server.close();
    }

    // --- Multiple handlers ---

    @Test
    void dispatchesToMultipleHandlers() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(noReconnect(server));

        CountDownLatch latch = new CountDownLatch(2);
        client.addHandler(msg -> latch.countDown());
        client.addHandler(msg -> latch.countDown());

        server.send(":nick!u@h PRIVMSG #chan :Hi");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        client.close();
        server.close();
    }

    // --- Handler throws — others continue ---

    @Test
    void handlerExceptionDoesNotAffectOtherHandlers() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(noReconnect(server));

        CountDownLatch latch = new CountDownLatch(1);
        client.addHandler(msg -> { throw new RuntimeException("boom"); });
        client.addHandler(msg -> latch.countDown());

        server.send(":nick!u@h PRIVMSG #chan :Hi");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        client.close();
        server.close();
    }

    // --- Ordering ---

    @Test
    void messagesAreDispatchedInOrder() throws Exception {
        var messages = List.of("one", "two", "three", "four", "five");

        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(noReconnect(server));

        CountDownLatch latch = new CountDownLatch(messages.size());
        List<String> received = new CopyOnWriteArrayList<>();
        client.addHandler(msg -> { received.add(msg.trailing()); latch.countDown(); });

        for (String m : messages) server.send(":n!u@h PRIVMSG #c :" + m);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(messages, received); // 1/120 chance of random match without order guarantee

        client.close();
        server.close();
    }

    // --- Burst messages ---

    @Test
    void noMessagesLostWhenManyArriveFast() throws Exception {
        int count = 100;
        FakeServer server = new FakeServer();
        IrcClient client = server.connectClient(noReconnect(server));

        CountDownLatch latch = new CountDownLatch(count);
        List<String> received = new CopyOnWriteArrayList<>();
        client.addHandler(msg -> { received.add(msg.trailing()); latch.countDown(); });

        for (int i = 0; i < count; i++) {
            server.send(":n!u@h PRIVMSG #c :msg-" + i);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all messages arrived within 5 seconds");
        assertEquals(count, received.size());

        client.close();
        server.close();
    }

    // --- PING/PONG ---

    @Test
    void autoRepliesToPing() throws Exception {
        FakeServer server = new FakeServer();
        List<IrcMessage> received = new CopyOnWriteArrayList<>();
        IrcClient client = server.connectClient(noReconnect(server));
        client.addHandler(received::add);

        server.send("PING :irc.server.com");
        assertEquals("PONG :irc.server.com", server.readLine());
        assertTrue(received.isEmpty(), "PING must not reach user handlers");

        client.close();
        server.close();
    }
}
