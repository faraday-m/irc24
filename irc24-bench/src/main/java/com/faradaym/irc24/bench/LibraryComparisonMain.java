package com.faradaym.irc24.bench;

import com.faradaym.irc24.client.IrcClient;
import com.faradaym.irc24.client.IrcClientConfig;
import com.faradaym.irc24.client.ReconnectStrategy;
import net.engio.mbassy.listener.Handler;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.event.connection.ClientConnectionEstablishedEvent;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Single-shot timing comparison: irc24 vs KittehIRCClientLib vs PircBotX.
 *
 * Each trial: start a loopback IRC server, connect N clients, measure wall time
 * until the last client receives 001 RPL_WELCOME. Repeat RUNS times, report median.
 *
 * Usage: java -cp benchmarks.jar com.faradaym.irc24.bench.LibraryComparisonMain [clients] [runs]
 */
public class LibraryComparisonMain {

    static final int DEFAULT_CLIENTS = 50;
    static final int DEFAULT_RUNS    = 7;
    static final int TIMEOUT_SEC     = 30;

    // -----------------------------------------------------------------------
    // Loopback server
    // -----------------------------------------------------------------------

    static int startServer(int n) throws IOException {
        ServerSocket srv = new ServerSocket(0);
        srv.setReuseAddress(true);
        Thread.ofVirtual().start(() -> {
            int seen = 0;
            while (seen < n) {
                try { Socket c = srv.accept(); seen++; Thread.ofVirtual().start(() -> handle(c)); }
                catch (IOException e) { break; }
            }
            try { srv.close(); } catch (IOException ignored) {}
        });
        return srv.getLocalPort();
    }

    static void handle(Socket conn) {
        try (conn;
             var in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
             var out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream()), true)) {
            String nick = "guest", line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("NICK ")) nick = line.substring(5).trim();
                if (line.startsWith("USER ")) {
                    out.print(":bench.local 001 " + nick + " :Welcome\r\n");
                    out.print(":bench.local 002 " + nick + " :host\r\n");
                    out.print(":bench.local 003 " + nick + " :date\r\n");
                    out.print(":bench.local 004 " + nick + " bench v o o\r\n");
                    out.print(":bench.local 375 " + nick + " :-\r\n");
                    out.print(":bench.local 376 " + nick + " :End of MOTD\r\n");
                    out.flush();
                    while (in.readLine() != null) {}
                    break;
                }
            }
        } catch (IOException ignored) {}
    }

    // -----------------------------------------------------------------------
    // Benchmarks
    // -----------------------------------------------------------------------

    static long runIrc24(int n) throws Exception {
        int port = startServer(n);
        var latch = new CountDownLatch(n);
        var clients = new ArrayList<IrcClient>(n);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            var cfg = IrcClientConfig.of("localhost", port, "irc24_" + i)
                    .withReconnect(ReconnectStrategy.noReconnect())
                    .withHandshakeTimeout(Duration.ofSeconds(TIMEOUT_SEC));
            var client = new IrcClient(cfg);
            clients.add(client);
            Thread.ofVirtual().start(() -> {
                try { client.start(); } catch (Exception ignored) { } finally { latch.countDown(); }
            });
        }
        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - t0;
        clients.forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
        return elapsed;
    }

    static long runKitteh(int n) throws Exception {
        int port = startServer(n);
        var latch = new CountDownLatch(n);
        var clients = new ArrayList<Client>(n);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            var client = Client.builder()
                    .server().host("localhost").port(port).secure(false).then()
                    .nick("kitteh" + i)
                    .build();
            client.getEventManager().registerEventListener(new KittehListener(latch));
            clients.add(client);
            client.connect();
        }
        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - t0;
        clients.forEach(Client::shutdown);
        return elapsed;
    }

    static class KittehListener {
        private final CountDownLatch latch;
        KittehListener(CountDownLatch l) { this.latch = l; }
        @Handler public void onConnect(ClientConnectionEstablishedEvent e) { latch.countDown(); }
    }

    static long runPircBotX(int n) throws Exception {
        int port = startServer(n);
        var latch   = new CountDownLatch(n);
        var bots    = new ArrayList<PircBotX>(n);
        var threads = new ArrayList<Thread>(n);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            var cfg = new Configuration.Builder()
                    .addServer("localhost", port)
                    .setName("pircbot" + i)
                    .setAutoReconnect(false)
                    .setAutoNickChange(false)
                    .addListener(new ListenerAdapter() {
                        @Override public void onConnect(ConnectEvent e) { latch.countDown(); }
                    })
                    .buildConfiguration();
            var bot = new PircBotX(cfg);
            bots.add(bot);
            var t = new Thread(() -> { try { bot.startBot(); } catch (Exception ignored) {} });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - t0;
        bots.forEach(b -> { try { b.sendIRC().quitServer("done"); } catch (Exception ignored) {} });
        for (Thread t : threads) t.join(3_000);
        return elapsed;
    }

    // -----------------------------------------------------------------------
    // Runner
    // -----------------------------------------------------------------------

    record Result(String name, int clients, long[] samples) {
        long median() {
            long[] s = samples.clone(); Arrays.sort(s);
            return s[s.length / 2];
        }
        long min() { long[] s = samples.clone(); Arrays.sort(s); return s[0]; }
        long max() { long[] s = samples.clone(); Arrays.sort(s); return s[s.length - 1]; }
    }

    public static void main(String[] args) throws Exception {
        int clients = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_CLIENTS;
        int runs    = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_RUNS;
        String only = args.length > 2 ? args[2] : null; // optional: "irc24", "kitteh", "pircbotx"

        System.out.printf("Library comparison: %d concurrent clients, %d runs each%n%n", clients, runs);

        var results = new ArrayList<Result>();

        for (var entry : List.of(
                Map.entry("irc24",   (ThrowingSupplier) () -> runIrc24(clients)),
                Map.entry("kitteh",  (ThrowingSupplier) () -> runKitteh(clients)),
                Map.entry("pircbotx",(ThrowingSupplier) () -> runPircBotX(clients)))) {

            if (only != null && !only.equals(entry.getKey())) continue;

            String name = entry.getKey();
            var supplier = entry.getValue();
            long[] samples = new long[runs];
            System.out.print(name + ": ");
            for (int r = 0; r < runs; r++) {
                try {
                    samples[r] = supplier.get();
                    System.out.printf("%.0fms ", samples[r] / 1e6);
                } catch (Exception e) {
                    samples[r] = TIMEOUT_SEC * 1_000_000_000L;
                    System.out.print("FAIL ");
                }
                Thread.sleep(200); // let TIME_WAIT drain a bit
            }
            System.out.println();
            results.add(new Result(name, clients, samples));
        }

        System.out.println();
        System.out.printf("%-12s  %6s  %6s  %6s%n", "Library", "median", "min", "max");
        System.out.println("-".repeat(36));
        for (var r : results) {
            System.out.printf("%-12s  %5.0fms  %5.0fms  %5.0fms%n",
                    r.name(), r.median() / 1e6, r.min() / 1e6, r.max() / 1e6);
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier { long get() throws Exception; }
}
