package com.faradaym.irc24.client;

import com.faradaym.irc24.parser.IrcMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IrcHandshakeTest {

    // --- Helper: FakeServer with full IRC handshake support ---

    /**
     * Starts a client with IRC handshake.
     * FakeServer accepts the connection, reads NICK/USER, and sends 001.
     */
    private IrcClient connectWithHandshake(FakeServer server, IrcClientConfig config) throws Exception {
        Thread acceptThread = Thread.ofVirtual().start(() -> {
            try {
                server.awaitConnection();
                // Read NICK and USER
                server.readLine(); // NICK ...
                server.readLine(); // USER ...
                // Send welcome
                server.send(":irc.test.net 001 " + config.nick() + " :Welcome to IRC");
            } catch (Exception ignored) {}
        });

        IrcClient client = new IrcClient(config);
        client.start(); // blocks until 001
        acceptThread.join(2000);
        return client;
    }

    private IrcClientConfig defaultConfig(FakeServer server) {
        return IrcClientConfig.of("localhost", server.port(), "testnick")
                .withReconnect(ReconnectStrategy.noReconnect());
    }

    // --- NICK/USER are sent on connect ---

    @Test
    void sendsNickAndUserOnConnect() throws Exception {
        FakeServer server = new FakeServer();
        Thread acceptThread = Thread.ofVirtual().start(() -> {
            try {
                server.awaitConnection();
                server.send(":irc.test.net 001 testnick :Welcome");
            } catch (Exception ignored) {}
        });

        IrcClient client = new IrcClient(defaultConfig(server));
        client.start();
        acceptThread.join(2000);

        // Read what the client sent (already buffered on the server side)
        String nick = server.readLine();
        String user = server.readLine();
        assertEquals("NICK testnick", nick);
        assertTrue(user.startsWith("USER testnick"), "USER command should start with USER testnick");

        client.close();
        server.close();
    }

    // --- PASS is sent before NICK/USER ---

    @Test
    void sendsPassBeforeNickWhenPasswordSet() throws Exception {
        FakeServer server = new FakeServer();
        Thread acceptThread = Thread.ofVirtual().start(() -> {
            try {
                server.awaitConnection();
                server.send(":irc.test.net 001 testnick :Welcome");
            } catch (Exception ignored) {}
        });

        IrcClient client = new IrcClient(
                defaultConfig(server).withPassword("secret123")
        );
        client.start();
        acceptThread.join(2000);

        assertEquals("PASS secret123", server.readLine());
        assertEquals("NICK testnick", server.readLine());
        assertTrue(server.readLine().startsWith("USER"));

        client.close();
        server.close();
    }

    // --- Handshake timeout if 001 never arrives ---

    @Test
    void throwsOnHandshakeTimeout() throws Exception {
        FakeServer server = new FakeServer();
        // Accept connection but never send 001
        Thread acceptThread = Thread.ofVirtual().start(server::awaitConnectionQuietly);

        IrcClient client = new IrcClient(
                defaultConfig(server).withHandshakeTimeout(Duration.ofMillis(300))
        );

        IOException ex = assertThrows(IOException.class, client::start);
        assertTrue(ex.getMessage().contains("001") || ex.getMessage().contains("handshake"),
                "Exception message should mention handshake or 001");

        acceptThread.join(1000);
        server.close();
    }

    // --- CTCP VERSION ---

    @Test
    void repliesWithCtcpVersion() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server).withCtcpVersion("TestClient 1.0"));

        // Send CTCP VERSION request (ASCII 0x01)
        server.send(":user!u@h PRIVMSG testnick :VERSION");

        String reply = server.readLine(); // wait for NOTICE reply
        assertNotNull(reply);
        assertTrue(reply.contains("VERSION TestClient 1.0"),
                "Reply should contain the client version, got: " + reply);

        client.close();
        server.close();
    }

    // --- getUsers() via NAMES ---

    @Test
    void getUsersReturnsParsedNicks() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server));

        CompletableFuture<List<String>> future = client.getUsers("#general");

        // Server responds to NAMES
        server.readLine(); // consume "NAMES #general" from client
        server.send(":irc.test.net 353 testnick = #general :@alice bob +carol");
        server.send(":irc.test.net 366 testnick #general :End of /NAMES list.");

        List<String> users = future.get(2, TimeUnit.SECONDS);
        assertEquals(List.of("alice", "bob", "carol"), users,
                "Mode prefixes (@, +) should be stripped");

        client.close();
        server.close();
    }

    // --- High-level commands ---

    @Test
    void sendMessageWritesPrivmsg() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server));

        client.commands().sendMessage("#chan", "hello world");

        assertEquals("PRIVMSG #chan :hello world", server.readLine());

        client.close();
        server.close();
    }

    @Test
    void joinWritesJoinCommand() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server));

        client.commands().join("#java");

        assertEquals("JOIN #java", server.readLine());

        client.close();
        server.close();
    }

    @Test
    void opWritesModeCommand() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server));

        client.commands().op("#chan", "alice");

        assertEquals("MODE #chan +o alice", server.readLine());

        client.close();
        server.close();
    }

    @Test
    void kickWritesKickCommand() throws Exception {
        FakeServer server = new FakeServer();
        IrcClient client = connectWithHandshake(server, defaultConfig(server));

        client.commands().kick("#chan", "troll", "spamming");

        assertEquals("KICK #chan troll :spamming", server.readLine());

        client.close();
        server.close();
    }
}
