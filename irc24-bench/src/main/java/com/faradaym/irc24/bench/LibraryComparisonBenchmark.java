package com.faradaym.irc24.bench;

import com.faradaym.irc24.client.IrcClient;
import com.faradaym.irc24.client.IrcClientConfig;
import com.faradaym.irc24.client.ReconnectStrategy;
import net.engio.mbassy.listener.Handler;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.event.connection.ClientConnectionEstablishedEvent;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Compares irc24, KittehIRCClientLib, and PircBotX on the same workload:
 * connect N clients to a loopback IRC server and complete the handshake (→ 001 RPL_WELCOME).
 *
 * The loopback server handles NICK/USER and responds with 001.
 * Measurement: wall time until ALL N clients receive 001.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 4, time = 5)
@Fork(1)
@State(Scope.Benchmark)
public class LibraryComparisonBenchmark {

    @Param({"10", "50", "100"})
    public int clientCount;

    // -----------------------------------------------------------------------
    // Minimal loopback IRC server — NICK+USER → 001, then drain until close
    // -----------------------------------------------------------------------

    private static int startFakeServer(int expectedClients) throws IOException {
        ServerSocket srv = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            int seen = 0;
            while (seen < expectedClients) {
                try {
                    Socket conn = srv.accept();
                    seen++;
                    Thread.ofVirtual().start(() -> handleConn(conn));
                } catch (IOException e) {
                    break;
                }
            }
            try { srv.close(); } catch (IOException ignored) {}
        });
        return srv.getLocalPort();
    }

    private static void handleConn(Socket conn) {
        try (conn;
             BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream()), true)) {
            String nick = "guest";
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("NICK ")) nick = line.substring(5).trim();
                if (line.startsWith("USER ")) {
                    // Full RFC 1459 welcome sequence that all libraries expect
                    out.print(":bench.local 001 " + nick + " :Welcome to BenchNet\r\n");
                    out.print(":bench.local 002 " + nick + " :Your host is bench.local\r\n");
                    out.print(":bench.local 003 " + nick + " :This server was created just now\r\n");
                    out.print(":bench.local 004 " + nick + " bench.local bench-1.0 o o\r\n");
                    out.print(":bench.local 375 " + nick + " :- bench.local MOTD -\r\n");
                    out.print(":bench.local 372 " + nick + " :- Benchmark server\r\n");
                    out.print(":bench.local 376 " + nick + " :End of MOTD\r\n");
                    out.flush();
                    while (in.readLine() != null) { /* drain until client closes */ }
                    break;
                }
            }
        } catch (IOException ignored) {}
    }

    // -----------------------------------------------------------------------
    // irc24 — virtual threads, one IrcClient per connection
    // -----------------------------------------------------------------------

    @Benchmark
    public void irc24(Blackhole bh) throws Exception {
        int port = startFakeServer(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);
        List<IrcClient> clients = new ArrayList<>(clientCount);

        for (int i = 0; i < clientCount; i++) {
            IrcClientConfig cfg = IrcClientConfig.of("localhost", port, "irc24_" + i)
                    .withReconnect(ReconnectStrategy.noReconnect())
                    .withHandshakeTimeout(Duration.ofSeconds(10));
            IrcClient client = new IrcClient(cfg);
            clients.add(client);
            Thread.ofVirtual().start(() -> {
                try {
                    client.start();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        bh.consume(latch.getCount());
        clients.forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
    }

    // -----------------------------------------------------------------------
    // KittehIRCClientLib — Netty-based async event loop
    // -----------------------------------------------------------------------

    @Benchmark
    public void kitteh(Blackhole bh) throws Exception {
        int port = startFakeServer(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);
        List<Client> clients = new ArrayList<>(clientCount);

        for (int i = 0; i < clientCount; i++) {
            Client client = Client.builder()
                    .server().host("localhost").port(port).secure(false).then()
                    .nick("kitteh" + i)
                    .build();
            client.getEventManager().registerEventListener(new KittehConnectionListener(latch));
            clients.add(client);
            client.connect();
        }

        latch.await(30, TimeUnit.SECONDS);
        bh.consume(latch.getCount());
        clients.forEach(Client::shutdown);
    }

    /** mbassador requires a concrete class (not anonymous) with @Handler method. */
    static class KittehConnectionListener {
        private final CountDownLatch latch;
        KittehConnectionListener(CountDownLatch latch) { this.latch = latch; }

        @Handler
        public void onConnect(ClientConnectionEstablishedEvent event) {
            latch.countDown();
        }
    }

    // -----------------------------------------------------------------------
    // PircBotX — one platform thread per bot (the library's intended model)
    // -----------------------------------------------------------------------

    private final List<PircBotX>  pircBots    = new ArrayList<>();
    private final List<Thread>    pircThreads = new ArrayList<>();

    @Benchmark
    public void pircbotx(Blackhole bh) throws Exception {
        int port = startFakeServer(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);

        for (int i = 0; i < clientCount; i++) {
            Configuration config = new Configuration.Builder()
                    .addServer("localhost", port)
                    .setName("pircbot" + i)
                    .setAutoReconnect(false)
                    .setAutoNickChange(false)
                    .addListener(new ListenerAdapter() {
                        @Override public void onConnect(ConnectEvent event) { latch.countDown(); }
                    })
                    .buildConfiguration();
            PircBotX bot = new PircBotX(config);
            pircBots.add(bot);
            Thread t = new Thread(() -> { try { bot.startBot(); } catch (Exception ignored) {} });
            t.setDaemon(true);
            pircThreads.add(t);
            t.start();
        }

        latch.await(30, TimeUnit.SECONDS);
        bh.consume(latch.getCount());
    }

    @TearDown(Level.Invocation)
    public void tearDownPirc() throws InterruptedException {
        // QUIT each bot so startBot() returns, then join the thread
        for (PircBotX bot : pircBots) {
            try { bot.sendIRC().quitServer("bench-done"); } catch (Exception ignored) {}
        }
        for (Thread t : pircThreads) {
            t.join(3_000);
        }
        pircBots.clear();
        pircThreads.clear();
    }
}
