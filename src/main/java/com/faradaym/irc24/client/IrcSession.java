package com.faradaym.irc24.client;

import java.io.IOException;

/**
 * Public API for an active IRC session — commands that can be sent to the server.
 *
 * Implemented by {@link IrcClient}; can be mocked in tests.
 */
public interface IrcSession {

    // --- Messaging ---

    void sendMessage(String target, String text) throws IOException;

    void sendNotice(String target, String text) throws IOException;

    // --- Nick ---

    void setNick(String nick) throws IOException;

    // --- Channels ---

    void join(String channel) throws IOException;

    void join(String channel, String key) throws IOException;

    void part(String channel) throws IOException;

    void part(String channel, String reason) throws IOException;

    void topic(String channel, String topic) throws IOException;

    // --- Moderation ---

    void kick(String channel, String nick, String reason) throws IOException;

    void op(String channel, String nick) throws IOException;

    void deop(String channel, String nick) throws IOException;

    void voice(String channel, String nick) throws IOException;

    void devoice(String channel, String nick) throws IOException;
}
