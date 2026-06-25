package com.faradaym.irc24.client.handler;

import com.faradaym.irc24.parser.IrcMessage;

@FunctionalInterface
public interface IrcEventHandler {
    void handle(IrcMessage message);
}
