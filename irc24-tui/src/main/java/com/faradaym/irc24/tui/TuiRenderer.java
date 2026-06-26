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
 *
 * Messages are rendered as styled spans: nicks get a consistent color derived
 * from their name hash, and a small subset of Markdown is rendered inline:
 *   **bold**   *italic*   `code`
 */
class TuiRenderer {

    private static final int CHAN_W = 16;
    private static final int USER_W = 16;

    // Colors picked for readability on dark terminal backgrounds.
    private static final TextColor.ANSI[] NICK_COLORS = {
            TextColor.ANSI.CYAN,
            TextColor.ANSI.GREEN,
            TextColor.ANSI.YELLOW,
            TextColor.ANSI.MAGENTA,
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.GREEN_BRIGHT,
            TextColor.ANSI.CYAN_BRIGHT,
            TextColor.ANSI.MAGENTA_BRIGHT,
    };

    // -----------------------------------------------------------------------
    // Span — a styled text fragment
    // -----------------------------------------------------------------------

    private record Span(String text, TextColor color, List<SGR> mods) {
        static Span plain(String text) {
            return new Span(text, TextColor.ANSI.DEFAULT, List.of());
        }
        static Span colored(String text, TextColor color) {
            return new Span(text, color, List.of());
        }
        static Span styled(String text, TextColor color, SGR... mods) {
            return new Span(text, color, List.of(mods));
        }
    }

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
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(TerminalPosition.TOP_LEFT_CORNER, sz, ' ');

        // ── Status bar ───────────────────────────────────────────────────────
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
            String ch      = channels.get(i);
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
            renderLine(g, msgX, row, parseSpans(wrapped.get(i), state.myNick), msgW);
        }
    }

    private void renderUsers(TextGraphics g, int userX, int contentH) {
        int msgW = state.termW - CHAN_W - USER_W - 2;
        if (msgW <= 0 || userX <= 0) return;
        List<String> ul       = state.chanUsers.getOrDefault(state.activeChannel, List.of());
        int          userRows = contentH - 1;
        drawHeader(g, userX, 1, "Users (" + ul.size() + ")", USER_W);
        for (int i = 0; i < ul.size() && i < userRows; i++) {
            String raw       = ul.get(i);
            String cleanNick = raw.replaceAll("^[@+%&~!]+", "");
            g.setForegroundColor(nickColor(cleanNick));
            g.putString(userX, 2 + i, cut(raw, USER_W));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
        if (ul.size() > userRows) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(userX, 1 + userRows, cut("+" + (ul.size() - userRows) + " more", USER_W));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    // -----------------------------------------------------------------------
    // Styled rendering
    // -----------------------------------------------------------------------

    /** Assigns a stable color to a nick based on its hash. */
    static TextColor.ANSI nickColor(String nick) {
        return NICK_COLORS[Math.abs(nick.hashCode()) % NICK_COLORS.length];
    }

    /**
     * Parses a stored message line into styled spans.
     *
     * Recognises:
     *   "<nick> text"  — nick colored + bold, body parsed for Markdown
     *   "*** text"     — system notice in yellow
     *   anything else  — Markdown only
     */
    static List<Span> parseSpans(String line, String myNick) {
        if (line.startsWith("<")) {
            int close = line.indexOf('>');
            if (close > 1) {
                String nick  = line.substring(1, close);
                TextColor fg = nick.equalsIgnoreCase(myNick)
                        ? TextColor.ANSI.WHITE_BRIGHT
                        : nickColor(nick);
                List<Span> spans = new ArrayList<>();
                spans.add(Span.styled("<" + nick + ">", fg, SGR.BOLD));
                spans.addAll(parseMarkdown(line.substring(close + 1)));
                return spans;
            }
        }
        if (line.startsWith("*** ")) {
            return List.of(Span.colored(line, TextColor.ANSI.YELLOW));
        }
        return parseMarkdown(line);
    }

    /**
     * Tokenises text into spans using a simple Markdown subset:
     *   **bold**   *italic*   `code`
     *
     * Markers are consumed (not included in span text).
     * Unclosed markers are treated as literal text.
     */
    static List<Span> parseMarkdown(String text) {
        List<Span> spans = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean bold = false, italic = false, code = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!code && c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                flush(spans, buf, bold, italic, false);
                bold = !bold;
                i += 2;
            } else if (!code && c == '*') {
                flush(spans, buf, bold, italic, false);
                italic = !italic;
                i++;
            } else if (c == '`') {
                flush(spans, buf, bold, italic, code);
                code = !code;
                i++;
            } else {
                buf.append(c);
                i++;
            }
        }
        flush(spans, buf, bold, italic, code);
        return spans;
    }

    private static void flush(List<Span> spans, StringBuilder buf, boolean bold, boolean italic, boolean code) {
        if (buf.isEmpty()) return;
        String text = buf.toString();
        buf.setLength(0);
        if (code) {
            spans.add(Span.colored(text, TextColor.ANSI.CYAN));
            return;
        }
        List<SGR> mods = new ArrayList<>(2);
        if (bold)   mods.add(SGR.BOLD);
        if (italic) mods.add(SGR.ITALIC);
        spans.add(new Span(text, TextColor.ANSI.DEFAULT, mods));
    }

    /** Renders a list of spans at (x, y), clipped to maxW columns. */
    private void renderLine(TextGraphics g, int x, int y, List<Span> spans, int maxW) {
        int used = 0;
        for (Span span : spans) {
            if (used >= maxW) break;
            String text = cut(span.text(), maxW - used);
            g.setForegroundColor(span.color());
            if (!span.mods().isEmpty()) g.enableModifiers(span.mods().toArray(new SGR[0]));
            g.putString(x + used, y, text);
            if (!span.mods().isEmpty()) g.disableModifiers(span.mods().toArray(new SGR[0]));
            used += text.length();
        }
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
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

    /**
     * Wraps a list of raw message strings to fit within {@code width} visible columns.
     * Markdown markers (**,  *, `) are invisible and excluded from the width count.
     */
    static List<String> wrapAll(List<String> lines, int width) {
        if (width <= 0) return lines;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (visibleLen(line) <= width) { out.add(line); continue; }
            int i = 0;
            boolean inCode = false;
            while (i < line.length()) {
                int visible = 0;
                boolean ic  = inCode;
                int j = i;
                while (j < line.length() && visible < width) {
                    char c = line.charAt(j);
                    if (!ic && c == '*' && j + 1 < line.length() && line.charAt(j + 1) == '*') {
                        j += 2;
                    } else if (!ic && c == '*') {
                        j++;
                    } else if (c == '`') {
                        ic = !ic;
                        j++;
                    } else {
                        visible++;
                        j++;
                    }
                }
                out.add(line.substring(i, j));
                inCode = ic;
                i = j;
            }
        }
        return out;
    }

    /** Visible column count of a string: marker characters (**,  *, `) are excluded. */
    static int visibleLen(String s) {
        boolean inCode = false;
        int len = 0, i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (!inCode && c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
            } else if (!inCode && c == '*') {
                i++;
            } else if (c == '`') {
                inCode = !inCode;
                i++;
            } else {
                len++;
                i++;
            }
        }
        return len;
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
