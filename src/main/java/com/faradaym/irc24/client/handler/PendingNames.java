package com.faradaym.irc24.client.handler;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * In-flight NAMES request: accumulates nicks from 353 (RPL_NAMREPLY) until 366 (RPL_ENDOFNAMES).
 */
public record PendingNames(List<String> users, CompletableFuture<List<String>> future) {}
