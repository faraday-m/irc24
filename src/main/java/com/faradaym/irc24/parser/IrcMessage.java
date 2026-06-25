package com.faradaym.irc24.parser;

import java.util.List;
import java.util.Map;

/**
 * Immutable representation of a parsed IRC message.
 * Format: [@tags] [:prefix] COMMAND [params] [:trailing]
 *
 * tags — IRCv3 message tags, empty map if absent
 */
public record IrcMessage(
        Map<String, String> tags,  // IRCv3, empty if absent
        String prefix,             // null if absent
        String command,
        List<String> params,
        String trailing            // null if absent
) {}
