package com.faradaym.irc24.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IrcMessagesTest {

    // --- Formatting ---

    @Test
    void privmsgFormatsCorrectly() {
        assertEquals("PRIVMSG #chan :hello world", IrcMessages.privmsg("#chan", "hello world"));
    }

    @Test
    void joinFormatsCorrectly() {
        assertEquals("JOIN #java", IrcMessages.join("#java"));
    }

    @Test
    void joinWithKeyFormatsCorrectly() {
        assertEquals("JOIN #secret mykey", IrcMessages.join("#secret", "mykey"));
    }

    @Test
    void partWithReasonFormatsCorrectly() {
        assertEquals("PART #chan :bye", IrcMessages.part("#chan", "bye"));
    }

    @Test
    void kickFormatsCorrectly() {
        assertEquals("KICK #chan troll :spamming", IrcMessages.kick("#chan", "troll", "spamming"));
    }

    @Test
    void opFormatsCorrectly() {
        assertEquals("MODE #chan +o alice", IrcMessages.op("#chan", "alice"));
    }

    @Test
    void deopFormatsCorrectly() {
        assertEquals("MODE #chan -o alice", IrcMessages.deop("#chan", "alice"));
    }

    @Test
    void userFormatsCorrectly() {
        assertEquals("USER myuser 0 * :My Name", IrcMessages.user("myuser", "My Name"));
    }

    @Test
    void ctcpReplyFormatsCorrectly() {
        String result = IrcMessages.ctcpReply("alice", "VERSION", "TestClient 1.0");
        assertEquals("NOTICE alice :VERSION TestClient 1.0", result);
    }

    @Test
    void ctcpRequestFormatsCorrectly() {
        String result = IrcMessages.ctcpRequest("alice", "VERSION");
        assertEquals("PRIVMSG alice :VERSION", result);
    }

    // --- CR/LF injection ---

    @Test
    void rejectsNewlineInText() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.privmsg("#chan", "hello\r\nQUIT"));
        assertTrue(ex.getMessage().contains("CR or LF"));
    }

    @Test
    void rejectsCarriageReturnInTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.privmsg("#chan\r", "text"));
    }

    @Test
    void rejectsNewlineInNick() {
        assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.nick("bad\nnick"));
    }

    @Test
    void rejectsNewlineInChannel() {
        assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.join("#chan\nJOIN #other"));
    }

    // --- Null / empty parameters ---

    @Test
    void rejectsNullTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.privmsg(null, "text"));
    }

    @Test
    void rejectsEmptyText() {
        assertThrows(IllegalArgumentException.class,
                () -> IrcMessages.privmsg("#chan", ""));
    }

    // --- Length limit ---

    @Test
    void rejectsTooLongMessage() {
        String longText = "x".repeat(510); // PRIVMSG #c :xxx... already exceeds 510
        assertThrows(MessageTooLongException.class,
                () -> IrcMessages.privmsg("#c", longText));
    }

    @Test
    void acceptsMessageAtLimit() {
        // "PRIVMSG #c :" = 12 bytes, leaving 498 bytes for the text
        String text = "x".repeat(498);
        assertDoesNotThrow(() -> IrcMessages.privmsg("#c", text));
    }

    @Test
    void tooLongExceptionCarriesBytes() {
        String longText = "x".repeat(510);
        MessageTooLongException ex = assertThrows(MessageTooLongException.class,
                () -> IrcMessages.privmsg("#c", longText));
        assertTrue(ex.actualBytes() > 510);
        assertEquals(510, ex.maxBytes());
    }
}
