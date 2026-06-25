package com.faradaym.irc24.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.util.*;

/**
 * Renders the three-column TUI layout onto a Lanterna {@link Screen}.
 *
 * <pre>
 *  Row 0:        status bar
 *  Rows 1..H-3:  [Channels │ Messages │ Users]
 *  Row H-2:      separator line
 *  Row H-1:      input line
 * </pre>
 */
class TuiRenderer {

    private static final int CHAN_W = 16;
    private static final int USER_W = 16;

    private final TuiState state;
    private final Screen   screen;
    private final String   host;

    TuiRenderer(TuiState state, Screen screen, String host) {
        this.state  = state;
        this.screen = screen;
        this.host   = host;
    }

    void render(StringBuilder input) throws IOException {
        TerminalSize sz = screen.doResizeIfNecessary();
        if (sz == null) sz = screen.getTerminalSize();
        int W = sz.getColumns(), H = sz.getRows();
        state.termW = W; state.termH = H;

        TextGraphics g = screen.newTextGraphics();
        // Fill via TextGraphics (not screen.clear()) — modifies back buffer only,
        // so Lanterna diffs properly and sends only changed cells each frame.
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(TerminalPosition.TOP_LEFT_CORNER, sz, ' ');

        // ── Status bar ──────────────────────────────────────────────────────
        g.setBackgroundColor(TextColor.ANSI.BLUE);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.enableModifiers(SGR.BOLD);
        String chan = state.activeChannel.isEmpty() ? "(no channel)" : state.activeChannel;
        g.putString(0, 0, pad(" irc24 | " + host + " | " + state.myNick + " | " + chan, W));
        g.disableModifiers(SGR.BOLD);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        int contentH = H - 3;
        int msgX     = CHAN_W + 1;
        int msgW     = W - CHAN_W - USER_W - 2;
        int userX    = W - USER_W;

        // ── Separators ───────────────────────────────────────────────────────
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (int r = 1; r < H - 2; r++) {
            g.putString(CHAN_W, r, "│");
            if (userX > msgX) g.putString(userX - 1, r, "│");
        }
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        renderChannels(g, contentH);
        renderMessages(g, msgX, msgW, contentH, H);
        renderUsers(g, userX, contentH);

        // ── Separator + input ────────────────────────────────────────────────
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(0, H - 2, "─".repeat(W));
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        String prompt = "> " + input;
        g.putString(0, H - 1, cut(prompt, W));
        screen.setCursorPosition(new TerminalPosition(Math.min(prompt.length(), W - 1), H - 1));
    }

    private void renderChannels(TextGraphics g, int contentH) {
        int chanRows  = contentH - 1;
        List<String> channels = state.channels;
        int activeIdx = channels.indexOf(state.activeChannel);
        int offset    = 0;
        if (activeIdx >= 0 && channels.size() > chanRows)
            offset = Math.min(channels.size() - chanRows, Math.max(0, activeIdx - chanRows / 2));

        drawHeader(g, 0, 1, "Channels", CHAN_W);
        if (offset > 0) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(0, 2, cut("↑ " + offset, CHAN_W));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
        for (int i = offset; i < channels.size() && (i - offset) < chanRows; i++) {
            String ch     = channels.get(i);
            boolean active = ch.equals(state.activeChannel);
            g.setForegroundColor(active ? TextColor.ANSI.GREEN : TextColor.ANSI.DEFAULT);
            if (active) g.enableModifiers(SGR.BOLD);
            g.putString(0, 2 + (i - offset), cut((active ? ">" : " ") + ch, CHAN_W));
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
        int below = channels.size() - offset - chanRows;
        if (below > 0) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(0, 1 + chanRows, cut("↓ " + below, CHAN_W));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private void renderMessages(TextGraphics g, int msgX, int msgW, int contentH, int H) {
        String key = state.activeChannel.isEmpty() ? "*" : state.activeChannel;
        ArrayDeque<String> rawMsgs = state.chanMessages.getOrDefault(key, new ArrayDeque<>());
        List<String> msgList;
        synchronized (rawMsgs) { msgList = new ArrayList<>(rawMsgs); }
        List<String> wrapped = wrapAll(msgList, msgW);

        int msgRows  = contentH - 1;
        int pin      = state.msgPin.getOrDefault(key, -1);
        int endIdx   = (pin < 0) ? wrapped.size() : Math.min(pin, wrapped.size());
        int startIdx = Math.max(0, endIdx - msgRows);
        boolean atBottom = (endIdx >= wrapped.size());

        String title = state.activeChannel.isEmpty() ? "irc24" : state.activeChannel;
        if (!atBottom) title = title + " [↓" + (wrapped.size() - endIdx) + " new]";
        drawHeader(g, msgX, 1, title, msgW);

        for (int i = startIdx; i < endIdx && i < wrapped.size(); i++) {
            int row = 2 + (i - startIdx);
            if (row >= H - 2) break;
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(msgX, row, cut(wrapped.get(i), msgW));
        }
    }

    private void renderUsers(TextGraphics g, int userX, int contentH) {
        int msgW = state.termW - CHAN_W - USER_W - 2;
        if (msgW <= 0 || userX <= 0) return;
        List<String> ul       = state.chanUsers.getOrDefault(state.activeChannel, List.of());
        int          userRows = contentH - 1;
        drawHeader(g, userX, 1, "Users (" + ul.size() + ")", USER_W);
        for (int i = 0; i < ul.size() && i < userRows; i++)
            g.putString(userX, 2 + i, cut(ul.get(i), USER_W));
        if (ul.size() > userRows) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(userX, 1 + userRows, cut("+" + (ul.size() - userRows) + " more", USER_W));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private void drawHeader(TextGraphics g, int x, int row, String title, int width) {
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.enableModifiers(SGR.BOLD);
        g.putString(x, row, cut(title, width));
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    // -----------------------------------------------------------------------
    // Static utilities (package-visible — also used by IrcTui.scrollMsg)
    // -----------------------------------------------------------------------

    static List<String> wrapAll(List<String> lines, int width) {
        if (width <= 0) return lines;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.length() <= width) { out.add(line); continue; }
            for (int i = 0; i < line.length(); i += width)
                out.add(line.substring(i, Math.min(i + width, line.length())));
        }
        return out;
    }

    static String cut(String s, int max) {
        if (max <= 0) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return cut(s, width);
        return s + " ".repeat(width - s.length());
    }
}
