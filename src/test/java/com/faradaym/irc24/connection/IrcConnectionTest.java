package com.faradaym.irc24.connection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class IrcConnectionTest {

    // --- Fragmented TCP ---

    @Test
    void readsMessageFragmentedAcrossMultiplePackets() throws IOException {
        // TCP split "PING :server\r\n" into two packets
        InputStream fragmented = new FragmentedInputStream(
                "PING :ser",
                "ver\r\n"
        );
        try (IrcConnection conn = IrcConnection.fromStreams(fragmented, new ByteArrayOutputStream())) {
            assertEquals("PING :server", conn.readLine());
        }
    }

    @Test
    void readsTwoMessagesMergedIntoOnePacket() throws IOException {
        // TCP merged two messages into a single read
        InputStream merged = toStream("PING :server\r\nPONG :client\r\n");
        try (IrcConnection conn = IrcConnection.fromStreams(merged, new ByteArrayOutputStream())) {
            assertEquals("PING :server", conn.readLine());
            assertEquals("PONG :client", conn.readLine());
        }
    }

    // --- Writing ---

    @Test
    void writeLineAppendsCRLF() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (IrcConnection conn = IrcConnection.fromStreams(toStream(""), out)) {
            conn.writeLine("NICK mynick");
        }
        assertEquals("NICK mynick\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writeLineFlushesImmediately() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (IrcConnection conn = IrcConnection.fromStreams(toStream(""), out)) {
            conn.writeLine("USER myuser 0 * :Real Name");
            // data is already in out without an explicit external flush
            assertTrue(out.size() > 0);
        }
    }

    // --- helpers ---

    private InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * InputStream that yields data in chunks, simulating TCP packet fragmentation.
     */
    static class FragmentedInputStream extends InputStream {
        private final byte[][] chunks;
        private int chunkIndex = 0;
        private int byteIndex = 0;

        FragmentedInputStream(String... parts) {
            chunks = new byte[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                chunks[i] = parts[i].getBytes(StandardCharsets.UTF_8);
            }
        }

        @Override
        public int read() {
            while (chunkIndex < chunks.length) { // while chunks remain
                if (byteIndex < chunks[chunkIndex].length) { // while current chunk has bytes
                    return chunks[chunkIndex][byteIndex++] & 0xFF; // byte is signed; return unsigned value
                }
                chunkIndex++; // chunk exhausted
                byteIndex = 0;
            }
            return -1; // all chunks exhausted
        }
    }
}
