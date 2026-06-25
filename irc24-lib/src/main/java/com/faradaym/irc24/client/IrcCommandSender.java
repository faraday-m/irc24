package com.faradaym.irc24.client;

import com.faradaym.irc24.protocol.IrcMessages;

import java.io.IOException;

/**
 * Implementation of {@link IrcSession}: formats commands via {@link IrcMessages}
 * and passes them to a {@link LineWriter}.
 *
 * Intentionally unaware of IrcConnection — accepts only a write function.
 * IrcClient supplies the lambda {@code line -> connection.writeLine(line)},
 * where {@code connection} is a volatile field that stays current after reconnect.
 *
 * Package-private: users interact via IrcClient or IrcSession.
 */
class IrcCommandSender implements IrcSession {

    @FunctionalInterface
    interface LineWriter {
        void writeLine(String line) throws IOException;
    }

    private final LineWriter writer;

    IrcCommandSender(LineWriter writer) {
        this.writer = writer;
    }

    // --- Messaging ---

    @Override
    public void sendMessage(String target, String text) throws IOException {
        writer.writeLine(IrcMessages.privmsg(target, text));
    }

    @Override
    public void sendNotice(String target, String text) throws IOException {
        writer.writeLine(IrcMessages.notice(target, text));
    }

    // --- Nick ---

    @Override
    public void setNick(String nick) throws IOException {
        writer.writeLine(IrcMessages.nick(nick));
    }

    // --- Channels ---

    @Override
    public void join(String channel) throws IOException {
        writer.writeLine(IrcMessages.join(channel));
    }

    @Override
    public void join(String channel, String key) throws IOException {
        writer.writeLine(IrcMessages.join(channel, key));
    }

    @Override
    public void part(String channel) throws IOException {
        writer.writeLine(IrcMessages.part(channel));
    }

    @Override
    public void part(String channel, String reason) throws IOException {
        writer.writeLine(IrcMessages.part(channel, reason));
    }

    @Override
    public void topic(String channel, String topic) throws IOException {
        writer.writeLine(IrcMessages.topic(channel, topic));
    }

    // --- Moderation ---

    @Override
    public void kick(String channel, String nick, String reason) throws IOException {
        writer.writeLine(IrcMessages.kick(channel, nick, reason));
    }

    @Override
    public void op(String channel, String nick) throws IOException {
        writer.writeLine(IrcMessages.op(channel, nick));
    }

    @Override
    public void deop(String channel, String nick) throws IOException {
        writer.writeLine(IrcMessages.deop(channel, nick));
    }

    @Override
    public void voice(String channel, String nick) throws IOException {
        writer.writeLine(IrcMessages.voice(channel, nick));
    }

    @Override
    public void devoice(String channel, String nick) throws IOException {
        writer.writeLine(IrcMessages.devoice(channel, nick));
    }
}
