# irc24

IRC client library for Java 24 with virtual threads.

## Features

- Full IRC handshake (PASS/NICK/USER → 001 RPL_WELCOME)
- PING/PONG keepalive (automatic)
- CTCP VERSION auto-reply
- TLS/SSL via `SSLContext` (or JVM default)
- Automatic reconnect with configurable strategy
- Auto-rejoin after reconnect
- Async `getUsers(channel)` via NAMES/353/366
- Per-handler message queues with circuit breaker isolation
- Runtime server switch via `updateConfig()`
- IRCv3 message tag parsing

## Requirements

- Java 24+
- Maven

## Quick start

```java
IrcClientConfig config = IrcClientConfig.of("irc.libera.chat", 6697, "mynick")
        .withTls(true)
        .withReconnect(ReconnectStrategy.exponentialBackoff());

IrcClient client = new IrcClient(config);
client.addHandler(msg -> System.out.println(msg.prefix() + " " + msg.command() + " " + msg.trailing()));

client.start(); // blocks until 001 RPL_WELCOME

client.commands().join("#java");
```

## Configuration

`IrcClientConfig` is an immutable record. Use `IrcClientConfig.of(host, port, nick)` for defaults, then chain `with*` methods:

| Method | Default | Description |
|--------|---------|-------------|
| `withTls(boolean)` | `false` | Enable TLS |
| `withSslContext(SSLContext)` | JVM default | Custom trust/key store |
| `withPassword(String)` | none | PASS command |
| `withReconnect(ReconnectStrategy)` | exponential backoff | Reconnect policy |
| `withAutoRejoin(boolean)` | `false` | Rejoin channels after reconnect |
| `withCtcpVersion(String)` | `"irc24"` | CTCP VERSION reply string |
| `withHandshakeTimeout(Duration)` | 10s | Time to wait for 001 |
| `withConnectTimeout(Duration)` | 30s | TCP connect timeout |
| `withCircuitBreakerThreshold(int)` | 3 | Failures before handler is disabled |

## Handlers

```java
// Add a handler — receives all non-internal IRC messages
client.addHandler(msg -> {
    if ("PRIVMSG".equals(msg.command())) {
        System.out.println("<" + msg.prefix() + "> " + msg.trailing());
    }
});
```

Each handler runs in its own virtual thread with a `LinkedBlockingQueue`, so a slow handler cannot block others. After `circuitBreakerThreshold` consecutive exceptions the handler is silently disabled.

## Getting channel users

```java
CompletableFuture<List<String>> users = client.getUsers("#java");
users.thenAccept(nicks -> System.out.println("Users: " + nicks));
```

## Package structure

```
com.faradaym.irc24
├── protocol/       IrcCommand, IrcReply, IrcMessages, MessageTooLongException
├── parser/         IrcMessageParser, IrcMessage
├── connection/     IrcConnection
└── client/         IrcClient, IrcClientConfig, IrcSession, IrcCommandSender,
                    ReconnectStrategy
    └── handler/    IrcEventHandler, InternalHandler, IrcInternalHandlers,
                    CircuitBreaker, PendingNames
```

## Running tests

```bash
mvn test
```

Tests use a loopback `FakeServer` (real `ServerSocket`) — no external network required.
