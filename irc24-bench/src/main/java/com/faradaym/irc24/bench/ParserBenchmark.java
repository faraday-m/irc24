package com.faradaym.irc24.bench;

import com.faradaym.irc24.parser.IrcMessage;
import com.faradaym.irc24.parser.IrcMessageParser;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Parser throughput: how many IRC messages/sec can we parse?
 *
 * Run: java -jar irc24-bench/target/benchmarks.jar ParserBenchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ParserBenchmark {

    private IrcMessageParser parser;

    // A realistic mix of IRC message types
    private static final String[] MESSAGES = {
            ":nick!user@host.example.com PRIVMSG #general :Hello, world!",
            ":irc.server.net 001 myNick :Welcome to the IRC network myNick",
            "PING :irc.server.net",
            ":nick!user@host PRIVMSG #chan :This is a longer message with more content for testing",
            ":irc.server.net 353 myNick = #general :@op +voiced normal another one more",
            ":irc.server.net 366 myNick #general :End of /NAMES list",
            "@time=2026-01-01T12:00:00Z;msgid=abc123 :nick!u@h PRIVMSG #chan :IRCv3 tagged message",
            ":nick!user@host JOIN #newchannel",
            ":nick!user@host PART #newchannel :Leaving",
            ":irc.server.net 433 * desiredNick :Nickname is already in use",
    };

    @Setup
    public void setup() {
        parser = new IrcMessageParser();
    }

    @Benchmark
    public void parsePrivmsg(Blackhole bh) {
        bh.consume(parser.parse(MESSAGES[0]));
    }

    @Benchmark
    public void parseWelcome(Blackhole bh) {
        bh.consume(parser.parse(MESSAGES[1]));
    }

    @Benchmark
    public void parsePing(Blackhole bh) {
        bh.consume(parser.parse(MESSAGES[2]));
    }

    @Benchmark
    public void parseNamreply(Blackhole bh) {
        bh.consume(parser.parse(MESSAGES[5]));
    }

    @Benchmark
    public void parseIrcv3Tagged(Blackhole bh) {
        bh.consume(parser.parse(MESSAGES[6]));
    }

    /** Round-robin over all message types to get a blended throughput number. */
    @Benchmark
    public void parseMixed(Blackhole bh) {
        for (String msg : MESSAGES) {
            bh.consume(parser.parse(msg));
        }
    }
}
