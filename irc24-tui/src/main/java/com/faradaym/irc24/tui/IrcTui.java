package com.faradaym.irc24.tui;

import com.faradaym.irc24.client.IrcClient;
import com.faradaym.irc24.client.IrcClientConfig;
import com.faradaym.irc24.parser.IrcMessage;
import com.faradaym.irc24.protocol.IrcCommand;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Terminal IRC client.
 *
 * Wires together {@link TuiState}, {@link TuiRenderer}, and {@link IrcClient}.
 * Responsible for: IRC event handling, keyboard input, and the render loop.
 *
 * Key bindings:
 *   Enter / Escape  — send / quit
 *   PageUp/Down, ↑↓ — scroll message history
 *   Tab / Shift+Tab — switch channel
 */
public class IrcTui implements AutoCloseable {

    private final IrcClient   client;
    private final Screen      screen;
    private final TuiState    state;
    private final TuiRenderer renderer;
    private final StringBuilder input = new StringBuilder();

    public IrcTui(IrcClientConfig config, String host) throws IOException {
        state    = new TuiState(config.nick());
        screen   = new DefaultTerminalFactory().createScreen();
        client   = new IrcClient(config);
        renderer = new TuiRenderer(state, screen, host);
        setupHandlers();
        Runtime.getRuntime().addShutdownHook(new Thread(this::silentClose));
    }

    // -----------------------------------------------------------------------
    // IRC event handlers
    // -----------------------------------------------------------------------

    private void setupHandlers() {
        client.addHandler(msg -> {
            switch (msg.command()) {
                case IrcCommand.PRIVMSG -> {
                    String text = msg.trailing() == null ? "" : msg.trailing();
                    if (!text.isEmpty() && text.charAt(0) == '') return; // CTCP
                    String target = msg.params().isEmpty() ? state.activeChannel : msg.params().getFirst();
                    if (target.equalsIgnoreCase(state.myNick)) target = nick(msg.prefix()); // DM
                    state.addMessage(target, "<" + nick(msg.prefix()) + "> " + text);
                }
                case IrcCommand.NOTICE -> {
                    String text = msg.trailing() == null ? "" : msg.trailing();
                    if (!text.isEmpty() && text.charAt(0) == '') return; // CTCP reply
                    state.addMessage(state.activeChannel.isEmpty() ? "*" : state.activeChannel,
                            "[notice] <" + nick(msg.prefix()) + "> " + text);
                }
                case IrcCommand.JOIN -> {
                    String ch  = channelParam(msg);
                    String who = nick(msg.prefix());
                    if (who.equalsIgnoreCase(state.myNick)) {
                        if (!state.channels.contains(ch)) state.channels.add(ch);
                        state.chanMessages.putIfAbsent(ch, new ArrayDeque<>());
                        state.chanUsers.putIfAbsent(ch, new CopyOnWriteArrayList<>());
                        state.activeChannel = ch; // auto-switch to newly joined channel
                        state.addMessage(ch, "*** You joined " + ch);
                        state.fetchUsers(ch, client);
                    } else {
                        state.chanUsers.computeIfAbsent(ch, k -> new CopyOnWriteArrayList<>()).add(who);
                        state.addMessage(ch, "*** " + who + " joined " + ch);
                    }
                }
                case IrcCommand.PART -> {
                    String ch  = msg.params().isEmpty() ? state.activeChannel : msg.params().getFirst();
                    String who = nick(msg.prefix());
                    if (who.equalsIgnoreCase(state.myNick)) {
                        state.channels.remove(ch);
                        state.msgPin.remove(ch);
                        if (state.activeChannel.equals(ch))
                            state.activeChannel = state.channels.isEmpty() ? "" : state.channels.getFirst();
                    } else {
                        List<String> ul = state.chanUsers.get(ch);
                        if (ul != null) ul.remove(who);
                        state.addMessage(ch, "*** " + who + " left " + ch
                                + (msg.trailing() != null ? " (" + msg.trailing() + ")" : ""));
                    }
                }
                case IrcCommand.QUIT -> {
                    String who    = nick(msg.prefix());
                    String reason = msg.trailing() != null ? " (" + msg.trailing() + ")" : "";
                    state.chanUsers.values().forEach(ul -> ul.remove(who));
                    state.channels.forEach(ch -> state.addMessage(ch, "*** " + who + " quit" + reason));
                }
                case IrcCommand.NICK -> {
                    String oldNick = nick(msg.prefix());
                    String newNick = msg.params().isEmpty()
                            ? (msg.trailing() != null ? msg.trailing() : "") : msg.params().getFirst();
                    if (oldNick.equalsIgnoreCase(state.myNick)) state.myNick = newNick;
                    state.chanUsers.values().forEach(ul -> {
                        int i = ul.indexOf(oldNick);
                        if (i >= 0) ul.set(i, newNick);
                    });
                    state.channels.forEach(ch -> state.addMessage(ch, "*** " + oldNick + " is now " + newNick));
                }
                default -> {
                    try {
                        int code = Integer.parseInt(msg.command());
                        if (code >= 400)
                            state.addMessage(state.activeChannel.isEmpty() ? "*" : state.activeChannel,
                                    "[" + msg.command() + "] " + msg.trailing());
                    } catch (NumberFormatException ignored) {}
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void start(String initialChannel) throws Exception {
        screen.startScreen();
        client.start();
        if (initialChannel != null && !initialChannel.isEmpty())
            client.commands().join(initialChannel);
        runLoop();
    }

    private void runLoop() throws IOException, InterruptedException {
        while (state.running) {
            handleInput();
            renderer.render(input);
            screen.refresh();
            Thread.sleep(50);
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private void handleInput() throws IOException {
        KeyStroke key = screen.pollInput();
        if (key == null) return;
        switch (key.getKeyType()) {
            case Enter      -> { String line = input.toString().trim(); input.setLength(0);
                                 if (!line.isEmpty()) processLine(line); }
            case Backspace  -> { if (!input.isEmpty()) input.deleteCharAt(input.length() - 1); }
            case Character  -> { if (key.getCharacter() != null) input.append(key.getCharacter()); }
            case Escape, EOF -> state.running = false;
            case PageUp     -> scrollMsg(-(state.termH - 4));
            case PageDown   -> scrollMsg(state.termH - 4);
            case ArrowUp    -> scrollMsg(-3);
            case ArrowDown  -> scrollMsg(3);
            case Tab        -> switchChannel(+1);
            case ReverseTab -> switchChannel(-1);
        }
    }

    private void processLine(String line) {
        try {
            if (!line.startsWith("/")) {
                if (!state.activeChannel.isEmpty()) {
                    client.commands().sendMessage(state.activeChannel, line);
                    state.addMessage(state.activeChannel, "<" + state.myNick + "> " + line);
                }
                return;
            }
            String[] parts = line.substring(1).split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1] : "";
            String bucket = state.activeChannel.isEmpty() ? "*" : state.activeChannel;
            switch (cmd) {
                case "join"   -> client.commands().join(arg);
                case "part"   -> { String ch = arg.isEmpty() ? state.activeChannel : arg;
                                   if (!ch.isEmpty()) client.commands().part(ch); }
                case "nick"   -> client.commands().setNick(arg);
                case "msg"    -> { String[] mp = arg.split("\\s+", 2);
                                   if (mp.length == 2) client.commands().sendMessage(mp[0], mp[1]); }
                case "switch" -> { if (state.channels.contains(arg)) state.activeChannel = arg; }
                case "quit"   -> { client.close(); state.running = false; }
                case "help"   -> state.addMessage(bucket,
                        "Commands: /join #ch  /part  /nick <n>  /msg <t> <text>  /quit"
                        + " | Scroll: PgUp/PgDn or ↑↓  | Switch channel: Tab/Shift+Tab");
                default       -> state.addMessage(bucket, "Unknown command: /" + cmd + " — /help");
            }
        } catch (Exception e) {
            state.addMessage(state.activeChannel.isEmpty() ? "*" : state.activeChannel,
                    "[error] " + e.getMessage());
        }
    }

    /**
     * Adjusts the pinned scroll position for the active channel.
     * delta < 0 = toward older messages; delta > 0 = toward newer.
     * Reaching the bottom removes the pin (follow-tail mode).
     */
    private void scrollMsg(int delta) {
        String key = state.activeChannel.isEmpty() ? "*" : state.activeChannel;
        ArrayDeque<String> raw = state.chanMessages.getOrDefault(key, new ArrayDeque<>());
        List<String> msgs;
        synchronized (raw) { msgs = new ArrayList<>(raw); }
        int msgW    = Math.max(1, state.termW - 16 - 16 - 2); // CHAN_W + USER_W + separators
        int wrapped = TuiRenderer.wrapAll(msgs, msgW).size();
        int msgRows = Math.max(1, state.termH - 4);
        int curPin  = state.msgPin.getOrDefault(key, -1);
        int curEnd  = (curPin < 0) ? wrapped : curPin;
        int newEnd  = Math.max(msgRows, Math.min(wrapped, curEnd + delta));
        if (newEnd >= wrapped) state.msgPin.remove(key);
        else                   state.msgPin.put(key, newEnd);
    }

    private void switchChannel(int direction) {
        if (state.channels.isEmpty()) return;
        int idx  = state.channels.indexOf(state.activeChannel);
        int next = ((idx < 0 ? 0 : idx) + direction + state.channels.size()) % state.channels.size();
        state.activeChannel = state.channels.get(next);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String nick(String prefix) {
        if (prefix == null) return "?";
        int i = prefix.indexOf('!');
        return i >= 0 ? prefix.substring(0, i) : prefix;
    }

    private static String channelParam(IrcMessage msg) {
        if (!msg.params().isEmpty()) return msg.params().getFirst();
        return msg.trailing() != null ? msg.trailing() : "";
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        state.running = false;
        try { screen.stopScreen(); } catch (Exception ignored) {}
        try { client.close();      } catch (Exception ignored) {}
    }

    private void silentClose() {
        try { close(); } catch (Exception ignored) {}
    }
}
