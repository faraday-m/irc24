package com.faradaym.irc24.parser;

import com.faradaym.irc24.protocol.MessageTooLongException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrcMessageParserTest {

    private final IrcMessageParser parser = new IrcMessageParser();

    // --- Full message ---

    @Test
    void parsesFullMessage() {
        IrcMessage msg = parser.parse(":nick!user@host PRIVMSG #channel :Hello world!");
        assertEquals("nick!user@host", msg.prefix());
        assertEquals("PRIVMSG", msg.command());
        assertEquals(List.of("#channel"), msg.params());
        assertEquals("Hello world!", msg.trailing());
    }

    // --- No prefix ---

    @Test
    void parsesMessageWithoutPrefix() {
        IrcMessage msg = parser.parse("PING :irc.server.com");
        assertNull(msg.prefix());
        assertEquals("PING", msg.command());
        assertEquals("irc.server.com", msg.trailing());
    }

    // --- Trailing with internal spaces ---

    @Test
    void trailingPreservesInternalSpaces() {
        IrcMessage msg = parser.parse(":nick!u@h PRIVMSG #chan :Hello beautiful world!");
        assertEquals("Hello beautiful world!", msg.trailing());
    }

    // --- Empty trailing ---

    @Test
    void parsesEmptyTrailing() {
        IrcMessage msg = parser.parse(":nick!u@h PRIVMSG #chan :");
        assertEquals("", msg.trailing());
    }

    // --- No trailing ---

    @Test
    void parsesMessageWithoutTrailing() {
        IrcMessage msg = parser.parse(":server 001 mynick");
        assertEquals("001", msg.command());
        assertEquals(List.of("mynick"), msg.params());
        assertNull(msg.trailing());
    }

    // --- Multiple params ---

    @Test
    void parsesMultipleParams() {
        IrcMessage msg = parser.parse(":server MODE #chan +o nick");
        assertEquals(List.of("#chan", "+o", "nick"), msg.params());
    }

    // --- Unicode ---

    @Test
    void parsesUnicodeTrailing() {
        IrcMessage msg = parser.parse(":nick!u@h PRIVMSG #chan :Привет мир!");
        assertEquals("Привет мир!", msg.trailing());
    }

    // --- IRCv3 tags ---

    @Test
    void parsesIrcV3Tags() {
        IrcMessage msg = parser.parse("@time=2026-06-25T10:00:00Z;msgid=abc :nick!u@h PRIVMSG #chan :Hi");
        assertEquals("2026-06-25T10:00:00Z", msg.tags().get("time"));
        assertEquals("abc", msg.tags().get("msgid"));
        assertEquals("nick!u@h", msg.prefix());
        assertEquals("Hi", msg.trailing());
    }

    @Test
    void parsesFlagTagWithoutValue() {
        IrcMessage msg = parser.parse("@draft/react :nick!u@h TAGMSG #chan");
        assertEquals("", msg.tags().get("draft/react"));
    }

    @Test
    void returnsEmptyTagsWhenAbsent() {
        IrcMessage msg = parser.parse(":nick!u@h PRIVMSG #chan :Hi");
        assertEquals(Map.of(), msg.tags());
    }

    // --- Max length (510 bytes after stripping CRLF, RFC 1459) ---

    @Test
    void rejectsAsciiMessageExceedingMaxBytes() {
        // prefix+command+params = ~24 bytes, trailing pushes total above 510
        String longTrailing = "x".repeat(500);
        assertThrows(MessageTooLongException.class,
                () -> parser.parse(":nick!u@h PRIVMSG #chan :" + longTrailing));
    }

    @Test
    void rejectsCyrillicMessageExceedingMaxBytes() {
        // Cyrillic = 2 bytes per char in UTF-8, 256 chars = 512 bytes — already above threshold
        String longTrailing = "я".repeat(256);
        assertThrows(MessageTooLongException.class,
                () -> parser.parse(":n!u@h PRIVMSG #c :" + longTrailing));
    }
}
