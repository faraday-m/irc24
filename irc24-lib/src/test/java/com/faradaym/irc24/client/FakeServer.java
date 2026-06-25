package com.faradaym.irc24.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight fake IRC server over real TCP loopback.
 * Supports disconnect and reconnect for resilience tests.
 */
class FakeServer implements Closeable {

    private final ServerSocket serverSocket;
    private volatile Socket serverSide;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;

    FakeServer() throws IOException {
        serverSocket = new ServerSocket(0);
    }

    /** TLS variant: accepts connections via SSLServerSocket */
    static FakeServer tls(SSLContext ctx) throws IOException {
        return new FakeServer(ctx);
    }

    private FakeServer(SSLContext ctx) throws IOException {
        serverSocket = ctx.getServerSocketFactory().createServerSocket(0);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * Starts accept() in background, performs IRC handshake (reads NICK/USER, sends 001),
     * creates and starts IrcClient. After this returns, send()/disconnect() are safe.
     */
    IrcClient connectClient(IrcClientConfig config) throws Exception {
        Thread acceptThread = Thread.ofVirtual().start(() -> {
            try {
                awaitConnection();
                // Read PASS (if present), NICK, USER — don't validate, just drain
                String line = reader.readLine();
                if (line != null && line.startsWith("PASS")) line = reader.readLine();
                if (line != null && line.startsWith("NICK")) reader.readLine(); // USER
                // Send 001 to unblock client.start()
                writer.write(":fake.irc.server 001 " + config.nick() + " :Welcome\r\n");
                writer.flush();
            } catch (Exception ignored) {}
        });

        IrcClient client = new IrcClient(config);
        client.start(); // blocks until 001
        acceptThread.join(2000);
        return client;
    }

    /**
     * Blocks until a client connects. Call from a virtual thread before starting IrcClient,
     * or after disconnect to wait for reconnect.
     */
    void awaitConnection() throws IOException {
        serverSide = serverSocket.accept();
        writer = new BufferedWriter(new OutputStreamWriter(serverSide.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(serverSide.getInputStream(), StandardCharsets.UTF_8));
    }

    void awaitConnectionQuietly() {
        try { awaitConnection(); } catch (IOException ignored) {}
    }

    /**
     * Like awaitConnection, but also sends 001 after reading NICK/USER.
     * Used in reconnect tests where the client repeats the handshake.
     */
    void awaitConnectionAndWelcome(String nick) throws IOException {
        awaitConnection();
        String line = reader.readLine();
        if (line != null && line.startsWith("PASS")) line = reader.readLine();
        if (line != null && line.startsWith("NICK")) reader.readLine(); // USER
        send(":fake.irc.server 001 " + nick + " :Welcome");
    }

    void awaitConnectionAndWelcomeQuietly(String nick) {
        try { awaitConnectionAndWelcome(nick); } catch (IOException ignored) {}
    }

    /**
     * Explicitly initiates TLS handshake on the server side.
     * Required for tests where the client rejects the certificate:
     * without this the client sends ClientHello and waits forever for ServerHello.
     * Throws SSLHandshakeException if the client rejected the cert — that is expected.
     */
    void startTlsHandshake() throws IOException {
        if (serverSide instanceof SSLSocket ssl) {
            ssl.startHandshake();
        }
    }

    void startTlsHandshakeQuietly() {
        try { startTlsHandshake(); } catch (IOException ignored) {}
    }

    /** Simulates server-side disconnect — sends EOF to client. */
    void disconnect() throws IOException {
        serverSide.close();
    }

    void send(String line) throws IOException {
        writer.write(line + "\r\n");
        writer.flush();
    }

    String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
