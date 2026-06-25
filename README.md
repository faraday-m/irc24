# irc24

IRC client library for Java 24 with virtual threads, and a TUI client built on top of it.

## Modules

| Module | Description |
|--------|-------------|
| `irc24-lib` | Core library — no UI dependencies |
| `irc24-tui` | Terminal UI client (Lanterna), depends on `irc24-lib` |

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

---

## TUI client

Three-column terminal interface: channel list · messages · user list.

### Run

```bash
# Build fat JAR (includes all dependencies)
mvn package -DskipTests

# Launch (defaults: irc.libera.chat:6697 TLS, nick=irc24bot, channel=#libera)
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar

# With options
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar \
  --host irc.libera.chat --port 6697 --nick mynick --channel "#libera"

# Plain TCP
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar --no-tls --port 6667
```

> **Note:** run via `java -jar`, not `mvn exec:java` — Maven redirects stdin and breaks terminal raw mode.

### Key bindings

| Key | Action |
|-----|--------|
| Enter | Send message / execute command |
| Escape | Quit |
| PageUp / ↑ | Scroll messages up (older) |
| PageDown / ↓ | Scroll messages down (newer) |
| Tab | Next channel |
| Shift+Tab | Previous channel |

### Slash commands

| Command | Description |
|---------|-------------|
| `/join #channel` | Join a channel (auto-switches to it) |
| `/part [#channel]` | Leave current or named channel |
| `/nick <newnick>` | Change nickname |
| `/msg <target> <text>` | Send a private message |
| `/switch #channel` | Switch active channel without rejoining |
| `/quit` | Disconnect and exit |
| `/help` | Show command reference |

---

## Library quick start

```java
IrcClientConfig config = IrcClientConfig.of("irc.libera.chat", 6697, "mynick")
        .withTls(true)
        .withReconnect(ReconnectStrategy.exponentialBackoff());

IrcClient client = new IrcClient(config);
client.addHandler(msg -> System.out.println(msg.prefix() + " " + msg.command() + " " + msg.trailing()));

client.start(); // blocks until 001 RPL_WELCOME

client.commands().join("#java");
```

### Maven dependency (after `mvn install`)

```xml
<dependency>
    <groupId>com.faradaym</groupId>
    <artifactId>irc24-lib</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
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
irc24-lib/src/main/java/com/faradaym/irc24/
├── protocol/       IrcCommand, IrcReply, IrcMessages, MessageTooLongException
├── parser/         IrcMessageParser, IrcMessage
├── connection/     IrcConnection
└── client/         IrcClient, IrcClientConfig, IrcSession, IrcCommandSender, ReconnectStrategy
    └── handler/    IrcEventHandler, InternalHandler, IrcInternalHandlers, CircuitBreaker, PendingNames

irc24-tui/src/main/java/com/faradaym/irc24/tui/
    IrcTui, TuiState, TuiRenderer, TuiMain
```

## Running tests

```bash
mvn test
# or only the library tests
mvn test -pl irc24-lib
```

Tests use a loopback `FakeServer` (real `ServerSocket`) — no external network required.
