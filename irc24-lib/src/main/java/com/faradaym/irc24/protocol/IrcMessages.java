package com.faradaym.irc24.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Formats outgoing IRC commands into wire-ready strings.
 *
 * Each method:
 *  1. Validates parameters for null/empty and absence of CR/LF (IRC injection protection).
 *  2. Builds the string.
 *  3. Checks the final length — no more than 510 UTF-8 bytes (RFC 1459: 512 − 2 CRLF).
 *
 * Throws {@link IllegalArgumentException} if a parameter is invalid,
 * {@link MessageTooLongException} if the string exceeds the limit.
 *
 * IrcClient passes the result directly to IrcConnection.writeLine().
 */
public final class IrcMessages {

    private IrcMessages() {}

    private static final int MAX_WIRE_BYTES = 510;
    public static final char CTCP = '';

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    public static String pass(String password) {
        requireParam("password", password);
        return build(IrcCommand.PASS + " " + password);
    }

    public static String nick(String nick) {
        requireToken("nick", nick);
        return build(IrcCommand.NICK + " " + nick);
    }

    public static String user(String username, String realName) {
        requireToken("username", username);
        requireParam("realName", realName);
        // RFC 1459: USER <username> <hostname> <servername> :<realname>
        // hostname and servername are ignored by the server — use placeholders
        return build(IrcCommand.USER + " " + username + " 0 * :" + realName);
    }

    public static String quit(String reason) {
        requireParam("reason", reason);
        return build(IrcCommand.QUIT + " :" + reason);
    }

    public static String quit() {
        return build(IrcCommand.QUIT);
    }

    // -----------------------------------------------------------------------
    // Messaging
    // -----------------------------------------------------------------------

    public static String privmsg(String target, String text) {
        requireParam("target", target);
        requireParam("text", text);
        return build(IrcCommand.PRIVMSG + " " + target + " :" + text);
    }

    public static String notice(String target, String text) {
        requireParam("target", target);
        requireParam("text", text);
        return build(IrcCommand.NOTICE + " " + target + " :" + text);
    }

    // -----------------------------------------------------------------------
    // CTCP
    // -----------------------------------------------------------------------

    /** CTCP request: PRIVMSG target :\x01TYPE\x01 */
    public static String ctcpRequest(String target, String type) {
        requireParam("target", target);
        requireParam("type", type);
        return build(IrcCommand.PRIVMSG + " " + target + " :" + CTCP + type + CTCP);
    }

    /** CTCP reply: NOTICE target :\x01TYPE reply\x01 */
    public static String ctcpReply(String target, String type, String reply) {
        requireParam("target", target);
        requireParam("type", type);
        requireParam("reply", reply);
        return build(IrcCommand.NOTICE + " " + target + " :" + CTCP + type + " " + reply + CTCP);
    }

    // -----------------------------------------------------------------------
    // Channels
    // -----------------------------------------------------------------------

    public static String join(String channel) {
        requireToken("channel", channel);
        return build(IrcCommand.JOIN + " " + channel);
    }

    public static String join(String channel, String key) {
        requireToken("channel", channel);
        requireToken("key", key);
        return build(IrcCommand.JOIN + " " + channel + " " + key);
    }

    public static String part(String channel) {
        requireToken("channel", channel);
        return build(IrcCommand.PART + " " + channel);
    }

    public static String part(String channel, String reason) {
        requireToken("channel", channel);
        requireParam("reason", reason);
        return build(IrcCommand.PART + " " + channel + " :" + reason);
    }

    public static String kick(String channel, String nick, String reason) {
        requireToken("channel", channel);
        requireToken("nick", nick);
        requireParam("reason", reason);
        return build(IrcCommand.KICK + " " + channel + " " + nick + " :" + reason);
    }

    public static String topic(String channel, String topic) {
        requireToken("channel", channel);
        requireParam("topic", topic);
        return build(IrcCommand.TOPIC + " " + channel + " :" + topic);
    }

    public static String names(String channel) {
        requireToken("channel", channel);
        return build(IrcCommand.NAMES + " " + channel);
    }

    // -----------------------------------------------------------------------
    // Mode
    // -----------------------------------------------------------------------

    /** Arbitrary MODE: channel flags [param...] */
    public static String mode(String target, String flags, String... params) {
        requireToken("target", target);
        requireToken("flags", flags);
        StringBuilder sb = new StringBuilder(IrcCommand.MODE).append(" ").append(target).append(" ").append(flags);
        for (String p : params) {
            requireToken("mode param", p);
            sb.append(" ").append(p);
        }
        return build(sb.toString());
    }

    public static String op(String channel, String nick) {
        return mode(channel, "+o", nick);
    }

    public static String deop(String channel, String nick) {
        return mode(channel, "-o", nick);
    }

    public static String voice(String channel, String nick) {
        return mode(channel, "+v", nick);
    }

    public static String devoice(String channel, String nick) {
        return mode(channel, "-v", nick);
    }

    // -----------------------------------------------------------------------
    // PING/PONG
    // -----------------------------------------------------------------------

    public static String pong(String server) {
        requireParam("server", server);
        return build(IrcCommand.PONG + " :" + server);
    }

    // -----------------------------------------------------------------------
    // Validation & building
    // -----------------------------------------------------------------------

    /**
     * Validates the final wire length and returns the string if within limits.
     * Throws {@link MessageTooLongException} otherwise.
     */
    private static String build(String line) {
        int bytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_WIRE_BYTES) {
            throw new MessageTooLongException(bytes, MAX_WIRE_BYTES);
        }
        return line;
    }

    /**
     * Validates that a parameter is not null, not empty, and contains no CR/LF.
     * CR or LF in a value would split the line into multiple commands — IRC injection.
     */
    private static void requireParam(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("IRC param '" + name + "' must not be null or empty");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "IRC param '" + name + "' must not contain CR or LF (IRC injection risk)");
        }
    }

    /** Like requireParam, but also forbids spaces — for nick/channel tokens that must be a single word. */
    private static void requireToken(String name, String value) {
        requireParam(name, value);
        if (value.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("IRC param '" + name + "' must not contain spaces");
        }
    }
}
