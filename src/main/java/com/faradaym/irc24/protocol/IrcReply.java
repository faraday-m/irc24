package com.faradaym.irc24.protocol;

/**
 * IRC numeric reply codes (RFC 1459 + common extensions).
 * Names follow the official convention — RPL_* for successful replies, ERR_* for errors.
 */
public final class IrcReply {
    private IrcReply() {}

    // --- Handshake ---
    public static final String RPL_WELCOME  = "001"; // :Welcome to IRC <nick>
    public static final String RPL_YOURHOST = "002";
    public static final String RPL_CREATED  = "003";
    public static final String RPL_MYINFO   = "004";
    public static final String RPL_ISUPPORT = "005"; // CHANMODES=, PREFIX=, etc.

    // --- WHO / WHOIS ---
    public static final String RPL_WHOISUSER    = "311";
    public static final String RPL_WHOISSERVER  = "312";
    public static final String RPL_ENDOFWHOIS   = "318";
    public static final String RPL_WHOISCHANNELS = "319";

    // --- Channels ---
    public static final String RPL_CHANNELMODEIS = "324";
    public static final String RPL_TOPIC         = "332";
    public static final String RPL_TOPICWHOTIME  = "333";
    public static final String RPL_NAMREPLY      = "353"; // nick list for a channel
    public static final String RPL_ENDOFNAMES    = "366"; // end of nick list

    // --- Errors ---
    public static final String ERR_NOSUCHNICK    = "401";
    public static final String ERR_NOSUCHCHANNEL = "403";
    public static final String ERR_CANNOTSENDTOCHAN = "404";
    public static final String ERR_NICKNAMEINUSE = "433";
    public static final String ERR_NOTONCHANNEL  = "442";
    public static final String ERR_CHANOPRIVSNEEDED = "482";
}
