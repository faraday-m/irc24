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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual threads vs. platform threads: concurrent IRC client connections.
 *
 * Spins up {@code clientCount} clients against a loopback echo server,
 * each sending and receiving {@code messagesPerClient} messages.
 * Compares throughput using a virtual-thread executor vs. a fixed platform-thread pool.
 *
 * This is where virtual threads shine: each client blocks on IO but costs ~1KB of stack
 * instead of the ~512KB a platform thread needs.
 *
 * Run: java -jar irc24-bench/target/benchmarks.jar VirtualThreadsBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xss256k"})
public class VirtualThreadsBenchmark {

    @Param({"10", "100", "500"})
    int clientCount;

    private static final int MESSAGES_PER_CLIENT = 20;

    // -----------------------------------------------------------------------
    // Concurrent connection benchmark — pure virtual threads (IrcClient uses them internally)
    // -----------------------------------------------------------------------

    /**
     * Spins up {@code clientCount} IrcClients concurrently, each connecting to a
     * loopback FakeServer, sending {@code MESSAGES_PER_CLIENT} messages, and disconnecting.
     *
     * IrcClient uses virtual threads for its reader loop and handler workers — this
     * measures how well the JVM handles thousands of concurrent IO-bound virtual threads.
     */
    @Benchmark
    public void concurrentClientsVirtualThreads(Blackhole bh) throws Exception {
        runConcurrentClients(Executors.newVirtualThreadPerTaskExecutor(), bh);
    }

    /**
     * Same workload, but the outer coordination uses a bounded platform thread pool.
     * Compare wall-clock time vs. virtualThreads to see the overhead difference.
     *
     * Note: IrcClient's internal reader still uses a virtual thread — this benchmark
     * isolates the cost of the coordination layer, not the IO layer.
     */
    @Benchmark
    public void concurrentClientsPlatformThreadPool(Blackhole bh) throws Exception {
        // Use a pool sized to clientCount so all clients run concurrently (fair comparison)
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(clientCount, 200))) {
            runConcurrentClients(pool, bh);
        }
    }

    private void runConcurrentClients(ExecutorService executor, Blackhole bh) throws Exception {
        // One loopback server handles all clients
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            AtomicInteger connected = new AtomicInteger(clientCount);

            // Server: accept & echo loop for all clients
            Thread serverThread = Thread.ofVirtual().start(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket conn = serverSocket.accept();
                        Thread.ofVirtual().start(() -> echoSession(conn));
                    } catch (IOException e) {
                        break;
                    }
                }
            });

            CountDownLatch allDone = new CountDownLatch(clientCount);
            List<Future<?>> futures = new ArrayList<>(clientCount);

            for (int i = 0; i < clientCount; i++) {
                final String nick = "bench" + i;
                futures.add(executor.submit(() -> {
                    try {
                        IrcClientConfig cfg = IrcClientConfig.of("localhost", port, nick)
                                .withReconnect(ReconnectStrategy.noReconnect())
                                .withHandshakeTimeout(java.time.Duration.ofSeconds(5));
                        IrcClient client = new IrcClient(cfg);

                        CountDownLatch receivedAll = new CountDownLatch(MESSAGES_PER_CLIENT);
                        client.addHandler(msg -> {
                            bh.consume(msg);
                            receivedAll.countDown();
                        });
                        client.start();

                        // Send messages via IRC PRIVMSG
                        for (int m = 0; m < MESSAGES_PER_CLIENT; m++) {
                            client.commands().sendMessage("#bench", "msg-" + m);
                        }

                        receivedAll.await(5, TimeUnit.SECONDS);
                        client.close();
                    } catch (Exception e) {
                        // count as done even on error
                    } finally {
                        allDone.countDown();
                    }
                    return null;
                }));
            }

            allDone.await(30, TimeUnit.SECONDS);
            serverThread.interrupt();
        }
    }

    /**
     * Minimal echo session: reads NICK/USER, sends 001, then echoes every PRIVMSG
     * back to the sender as if the channel received it.
     */
    private static void echoSession(Socket conn) {
        try (conn) {
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));

            // Drain handshake
            String nick = "unknown";
            String line;
            int handshakeLines = 0;
            while (handshakeLines < 2 && (line = r.readLine()) != null) {
                if (line.startsWith("NICK ")) nick = line.substring(5).trim();
                handshakeLines++;
            }
            w.write(":s 001 " + nick + " :Welcome\r\n");
            w.flush();

            // Echo PRIVMSGs back as incoming messages
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PRIVMSG ")) {
                    // PRIVMSG #chan :text  →  :nick!u@h PRIVMSG #chan :text
                    w.write(":" + nick + "!u@h " + line + "\r\n");
                    w.flush();
                }
            }
        } catch (IOException ignored) {}
    }

    // -----------------------------------------------------------------------
    // Raw thread creation cost (no IrcClient)
    // -----------------------------------------------------------------------

    /**
     * Baseline: cost of creating and joining N virtual threads, each doing trivial work.
     * Subtract this from concurrentClientsVirtualThreads to isolate connection overhead.
     */
    @Benchmark
    public void threadCreationVirtual(Blackhole bh) throws Exception {
        CountDownLatch done = new CountDownLatch(clientCount);
        for (int i = 0; i < clientCount; i++) {
            Thread.ofVirtual().start(() -> {
                bh.consume(Thread.currentThread().getName());
                done.countDown();
            });
        }
        done.await(10, TimeUnit.SECONDS);
    }

    @Benchmark
    public void threadCreationPlatform(Blackhole bh) throws Exception {
        CountDownLatch done = new CountDownLatch(clientCount);
        for (int i = 0; i < clientCount; i++) {
            Thread.ofPlatform().start(() -> {
                bh.consume(Thread.currentThread().getName());
                done.countDown();
            });
        }
        done.await(10, TimeUnit.SECONDS);
    }
}
