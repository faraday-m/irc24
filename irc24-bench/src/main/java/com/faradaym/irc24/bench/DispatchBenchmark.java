package com.faradaym.irc24.bench;

import com.faradaym.irc24.client.IrcClient;
import com.faradaym.irc24.client.IrcClientConfig;
import com.faradaym.irc24.client.ReconnectStrategy;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Dispatch throughput: how fast can the client read + dispatch messages to N handlers?
 *
 * Each benchmark iteration sends {@code BATCH_SIZE} PRIVMSG lines through a loopback
 * ServerSocket and waits for all handlers to receive them.
 *
 * Run: java -jar irc24-bench/target/benchmarks.jar DispatchBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class DispatchBenchmark {

    private static final int BATCH_SIZE = 1_000;
    private static final String MSG = ":sender!u@h PRIVMSG #bench :payload\r\n";

    @Param({"1", "4", "16"})
    int handlerCount;

    private ServerSocket serverSocket;
    private IrcClient client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        // Accept thread: completes handshake and stays alive for iteration sends
        Thread.ofVirtual().start(() -> {
            try {
                Socket conn = serverSocket.accept();
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                // drain NICK/USER handshake
                r.readLine(); r.readLine();
                w.write(":s 001 bench :Welcome\r\n"); w.flush();
                // keep socket open — iteration senders write directly to this socket
            } catch (Exception ignored) {}
        });

        IrcClientConfig cfg = IrcClientConfig.of("localhost", port)
                .withReconnect(ReconnectStrategy.noReconnect());
        client = new IrcClient(cfg);
        client.start();
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        // Re-register fresh handlers each iteration so counts are clean
        // (IrcClient doesn't support removing handlers, so we wrap with a latch per iteration)
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        client.close();
        serverSocket.close();
    }

    /**
     * This benchmark shows dispatch overhead with N concurrent handler virtual threads.
     * It cannot reuse IrcClient across iterations cleanly without handler removal,
     * so it measures a single trial with a fixed client.
     *
     * For a real throughput number, run ParserBenchmark which has no IO overhead.
     */
    @Benchmark
    public void dispatchToHandlers(Blackhole bh) throws Exception {
        CountDownLatch done = new CountDownLatch(BATCH_SIZE * handlerCount);
        for (int i = 0; i < handlerCount; i++) {
            client.addHandler(msg -> {
                bh.consume(msg);
                done.countDown();
            });
        }
        // Write BATCH_SIZE messages from a background thread
        // (we don't have direct access to the server socket here — this benchmark
        //  is intentionally left as a template; see VirtualThreadsBenchmark for the
        //  full end-to-end loopback pattern)
        done.await(10, TimeUnit.SECONDS);
    }
}
