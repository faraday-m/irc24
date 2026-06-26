package com.faradaym.irc24.tui;

import com.faradaym.irc24.client.IrcClientConfig;
import com.faradaym.irc24.client.ReconnectStrategy;

import java.time.Duration;

/**
 * Entry point for the TUI client.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass=com.faradaym.irc24.tui.TuiMain
 *
 * Options (all optional):
 *   --host <host>       IRC server hostname   (default: irc.libera.chat)
 *   --port <port>       IRC server port       (default: 6697)
 *   --nick <nick>       Your nickname         (default: irc24bot)
 *   --channel <#chan>   Channel to join       (default: #libera)
 *   --no-tls            Disable TLS
 *
 * Key bindings:
 *   Enter      Send message / execute command
 *   Escape     Quit
 *
 * Slash commands:
 *   /join #channel
 *   /part [#channel]
 *   /nick <newnick>
 *   /msg <target> <text>
 *   /switch #channel
 *   /quit
 *   /help
 */
public class TuiMain {

    public static void main(String[] args) throws Exception {
        String host    = "localhost";
        int    port    = 6667;
        String nick    = "irc24bot";
        String channel = "#chat";
        boolean tls    = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host"    -> host    = args[++i];
                case "--port"    -> port    = Integer.parseInt(args[++i]);
                case "--nick"    -> nick    = args[++i];
                case "--channel" -> channel = args[++i];
                case "--no-tls"  -> tls     = false;
            }
        }

        IrcClientConfig config = IrcClientConfig.of(host, port, nick)
                .withTls(tls)
                .withHandshakeTimeout(Duration.ofSeconds(30))
                .withReconnect(ReconnectStrategy.exponentialBackoff());

        try (IrcTui tui = new IrcTui(config, host)) {
            tui.start(channel);
        }
    }
}
