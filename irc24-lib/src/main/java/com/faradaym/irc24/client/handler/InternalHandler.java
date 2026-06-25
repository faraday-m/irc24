package com.faradaym.irc24.client.handler;

import com.faradaym.irc24.parser.IrcMessage;

import java.io.IOException;

/**
 * Internal handler for a single IRC message type.
 * Returns {@code true} if the message is consumed — user handlers will not receive it.
 */
@FunctionalInterface
public interface InternalHandler {
    boolean handle(IrcMessage msg) throws IOException;
}
