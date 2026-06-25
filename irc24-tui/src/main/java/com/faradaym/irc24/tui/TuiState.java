package com.faradaym.irc24.tui;

import com.faradaym.irc24.client.IrcClient;

import java.util.*;
import java.util.concurrent.*;

/**
 * Shared mutable state for the TUI: channel messages, user lists, scroll positions.
 * Written by IRC virtual threads via addMessage(); read by the render loop.
 */
class TuiState {

    static final int MAX_MSGS = 500;

    final Map<String, ArrayDeque<String>> chanMessages = new ConcurrentHashMap<>();
    final Map<String, List<String>>       chanUsers    = new ConcurrentHashMap<>();
    final List<String>                    channels     = new CopyOnWriteArrayList<>();

    volatile String  activeChannel = "";
    volatile String  myNick;
    volatile boolean running       = true;

    /**
     * Per-channel message scroll position.
     * Absent / -1 = follow-tail (always show newest).
     * N = absolute end-line index into the wrapped line list (pinned view).
     */
    final Map<String, Integer> msgPin = new ConcurrentHashMap<>();

    /** Cached terminal dimensions — updated each render frame, read by scrollMsg(). */
    volatile int termW = 80, termH = 24;

    TuiState(String nick) {
        this.myNick = nick;
        chanMessages.put("*", new ArrayDeque<>()); // bucket for pre-join server notices
    }

    void addMessage(String channel, String text) {
        if (channel == null || channel.isEmpty()) channel = "*";
        ArrayDeque<String> q = chanMessages.computeIfAbsent(channel, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(text);
            if (q.size() > MAX_MSGS) q.removeFirst();
        }
    }

    void fetchUsers(String channel, IrcClient client) {
        try {
            client.getUsers(channel).thenAccept(nicks ->
                    chanUsers.put(channel, new CopyOnWriteArrayList<>(nicks)));
        } catch (Exception ignored) {}
    }
}
