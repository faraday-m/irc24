package com.faradaym.irc24.parser;

import com.faradaym.irc24.protocol.MessageTooLongException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses raw IRC message strings into {@link IrcMessage}.
 * Spec: RFC 1459 — max 512 bytes including CRLF, so 510 bytes of actual content.
 * IRCv3 message tags supported: @key=value;key2=value2
 */
public class IrcMessageParser {

    // 512 per RFC 1459, minus 2 for CRLF stripped before parsing
    private static final int MAX_BYTES = 510;

    public IrcMessage parse(String raw) {
        int byteLength = raw.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_BYTES) {
            throw new MessageTooLongException(byteLength);
        }

        // Extract IRCv3 tags: @key=value;key2=value2 ...
        Map<String, String> tags = Map.of();
        if (raw.startsWith("@")) {
            int spaceIdx = raw.indexOf(' ');
            tags = parseTags(raw.substring(1, spaceIdx));
            raw = raw.substring(spaceIdx + 1);
        }

        String prefix = null;
        String trailing = null;

        // Extract prefix
        if (raw.startsWith(":")) {
            int spaceIdx = raw.indexOf(' ');
            prefix = raw.substring(1, spaceIdx);
            raw = raw.substring(spaceIdx + 1);
        }

        // Extract trailing
        int trailingIdx = raw.indexOf(" :");
        if (trailingIdx != -1) {
            trailing = raw.substring(trailingIdx + 2);
            raw = raw.substring(0, trailingIdx);
        }

        // Remaining: command + params
        String[] parts = raw.strip().split(" ");
        String command = parts[0];

        List<String> params = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) params.add(parts[i]);
        }

        return new IrcMessage(tags, prefix, command, List.copyOf(params), trailing);
    }

    // @time=2026-01-01T00:00:00Z;msgid=abc → {time: "2026-01-01T00:00:00Z", msgid: "abc"}
    private Map<String, String> parseTags(String raw) {
        Map<String, String> tags = new HashMap<>();
        for (String token : raw.split(";")) {
            int eq = token.indexOf('=');
            if (eq == -1) {
                tags.put(token, ""); // flag tag with no value
            } else {
                tags.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return Map.copyOf(tags);
    }
}
