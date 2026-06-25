package com.faradaym.irc24.protocol;

/**
 * Thrown when an IRC message exceeds 510 bytes (RFC 1459: 512 − 2 for CRLF).
 * Used both when parsing incoming messages and when formatting outgoing ones.
 */
public class MessageTooLongException extends RuntimeException {

    private final int actualBytes;
    private final int maxBytes;

    public MessageTooLongException(int actualBytes) {
        this(actualBytes, 510);
    }

    public MessageTooLongException(int actualBytes, int maxBytes) {
        super("IRC message too long: " + actualBytes + " bytes (max " + maxBytes + ")");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public int actualBytes() { return actualBytes; }
    public int maxBytes()    { return maxBytes; }
}
