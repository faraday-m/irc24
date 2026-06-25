package com.faradaym.irc24.connection;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Low-level IRC connection: reads/writes raw IRC lines over TCP.
 *
 * BufferedReader handles TCP fragmentation transparently —
 * it accumulates bytes until \r\n regardless of packet boundaries.
 */
public class IrcConnection implements Closeable {

    private final Socket socket; // null for stream-based construction; held to prevent GC
    private final BufferedReader reader;
    private final BufferedWriter writer;

    /** Production: plain TCP, no timeout */
    public IrcConnection(String host, int port) throws IOException {
        this(host, port, Duration.ofSeconds(30), false);
    }

    /** Production: plain or TLS socket with connect timeout */
    public IrcConnection(String host, int port, Duration connectTimeout) throws IOException {
        this(host, port, connectTimeout, false);
    }

    /** Plain or TLS socket with connect timeout, using the JVM-default SSLContext */
    public IrcConnection(String host, int port, Duration connectTimeout, boolean tls) throws IOException {
        this(host, port, connectTimeout, tls, null);
    }

    /** Plain or TLS socket with connect timeout and an optional custom SSLContext */
    public IrcConnection(String host, int port, Duration connectTimeout, boolean tls, SSLContext sslContext)
            throws IOException {
        this(createSocket(host, port, connectTimeout, tls, sslContext));
    }

    private static Socket createSocket(String host, int port, Duration timeout, boolean tls, SSLContext ctx)
            throws IOException {
        SSLSocketFactory factory = tls
                ? (ctx != null ? ctx.getSocketFactory() : (SSLSocketFactory) SSLSocketFactory.getDefault())
                : null;
        Socket s = factory != null ? factory.createSocket() : new Socket();
        s.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
        // SSLSocket.connect() performs the TCP connect but not the TLS handshake.
        // startHandshake() is called implicitly on the first read/write — throws IOException on cert failure.
        return s;
    }

    public IrcConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private IrcConnection(InputStream in, OutputStream out) {
        this.socket = null;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /** For testing: create connection from arbitrary streams */
    public static IrcConnection fromStreams(InputStream in, OutputStream out) {
        return new IrcConnection(in, out);
    }

    /**
     * Blocks until a full IRC line is available.
     * Strips trailing \r if present (BufferedReader strips \n).
     *
     * @return raw IRC message without CRLF, or null on EOF
     */
    public String readLine() throws IOException {
        return reader.readLine(); // handles fragmentation; strips \r\n
    }

    /**
     * Sends a raw IRC line, appending \r\n and flushing immediately.
     */
    public void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            // Close the socket directly — this interrupts a blocked readLine() in another thread
            // without competing for the BufferedReader's internal lock.
            // reader/writer close automatically through the underlying streams.
            socket.close();
        } else {
            // stream-based (tests): both ends under our control, no race condition
            try { writer.close(); } catch (IOException ignored) {}
            try { reader.close(); } catch (IOException ignored) {}
        }
    }
}
