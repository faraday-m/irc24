package com.faradaym.irc24.protocol;

/**
 * IRC command strings (RFC 1459 + IRCv3).
 * Use these constants instead of string literals wherever you compare msg.command().
 */
public final class IrcCommand {
    private IrcCommand() {}

    // Connection
    public static final String PASS  = "PASS";
    public static final String NICK  = "NICK";
    public static final String USER  = "USER";
    public static final String QUIT  = "QUIT";
    public static final String PING  = "PING";
    public static final String PONG  = "PONG";

    // Channels
    public static final String JOIN  = "JOIN";
    public static final String PART  = "PART";
    public static final String KICK  = "KICK";
    public static final String TOPIC = "TOPIC";
    public static final String NAMES = "NAMES";
    public static final String LIST  = "LIST";

    // Messaging
    public static final String PRIVMSG = "PRIVMSG";
    public static final String NOTICE  = "NOTICE";

    // Access control
    public static final String MODE   = "MODE";
    public static final String INVITE = "INVITE";

    // Info
    public static final String WHO   = "WHO";
    public static final String WHOIS = "WHOIS";
    public static final String WHOWAS = "WHOWAS";
}
