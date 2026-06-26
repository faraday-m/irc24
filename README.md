# irc24

IRC client library for Java 24 with virtual threads, and a TUI client built on top of it.

## Modules

| Module | Description |
|--------|-------------|
| `irc24-lib` | Core library — no UI dependencies |
| `irc24-tui` | Terminal UI client (Lanterna), depends on `irc24-lib` |
| `irc24-bench` | JMH microbenchmarks |

## Why virtual threads?

Traditional IRC clients use either blocking I/O on platform threads (one thread per connection — expensive) or non-blocking NIO with manual state machines (complex). Virtual threads give the simplicity of blocking I/O at near-zero thread cost.

### JMH benchmark results vs. other libraries (Apple M-series)

Measured with `LibraryComparisonBenchmark`: N clients connect to a loopback IRC server and complete the full handshake (NICK+USER → 001 RPL_WELCOME). Time = wall time until the last client receives welcome.

Each library benchmarked in its own JVM process (no cross-contamination of thread pools). Warm median over 3 runs, Apple M-series, Java 24.

| Library | Threading model | 10 clients | 50 clients | 100 clients | 1000 clients |
|---|---|---|---|---|---|
| **irc24** | Virtual threads | **2 ms** | **8 ms** | **34 ms** | **87 ms** |
| KittehIRCClientLib | Netty NIO | 24 ms | 77 ms | 81 ms | 806 ms |
| PircBotX | Platform threads | 7 ms | ❌ | ❌ | ❌ |

irc24 scales **linearly** (~0.087 ms/client). At 1000 clients it is **9× faster** than KittehIRCClientLib.

**PircBotX** fails at 50+ clients: one bot = one platform thread + a `CachedThreadPool` for listeners (~3 threads). At 50 bots that is ~200 OS threads — the system limit is exhausted. This is not a benchmark artefact; it is the fundamental cost of the platform-thread-per-connection model that virtual threads eliminate.

Parser throughput (single thread): **~100–130 M messages/sec** for typical IRC lines.

### vs. other Java IRC libraries

| | irc24 | [KittehIRCClientLib](https://github.com/KittehOrganization/KittehIRCClientLib) | [PircBotX](https://github.com/pircbotx/pircbotx) |
|---|---|---|---|
| Java version | 24 | 11+ | 8+ |
| Threading model | Virtual threads | Netty NIO | Platform threads (1 per bot) |
| Reconnect | Configurable strategy | Built-in | Built-in |
| IRCv3 tags | Parsed | Full IRCv3 suite | No |
| TLS | Yes | Yes | Yes |
| Unicode nicks | Yes (auto-sanitizes USER) | Yes | No |
| Message validation | CR/LF injection, 510-byte limit | Partial | No |
| Dependencies | Zero (lib) | Netty, Guava, … | Guava, SLF4J, … |

irc24 is intentionally minimal — no command abstraction tower, no plugin system, no event bus framework. You get parsed messages and send strings via a thin `IrcMessages` factory. The value is in the threading model and the zero-dependency core.

---

## Features

- Full IRC handshake (PASS/NICK/USER → 001 RPL_WELCOME)
- Unicode nicks (Cyrillic, CJK, etc.) — `user` field auto-sanitized to ASCII
- PING/PONG keepalive (automatic)
- CTCP VERSION auto-reply
- TLS/SSL via `SSLContext` (or JVM default)
- Automatic reconnect with configurable strategy
- Auto-rejoin after reconnect
- Async `getUsers(channel)` via NAMES/353/366
- Per-handler message queues with circuit breaker isolation
- Runtime server switch via `updateConfig()`
- IRCv3 message tag parsing
- Nick coloring + Markdown rendering in TUI (`**bold**`, `*italic*`, `` `code` ``)

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

# Launch (defaults: localhost:6667 plain TCP, nick=irc24bot, channel=#chat)
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar

# Connect to a public network
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar \
  --host irc.libera.chat --port 6697 --nick mynick --channel "#libera"

# Unicode nick
java -jar irc24-tui/target/irc24-tui-1.0-SNAPSHOT-fat.jar --nick Привет
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

## Benchmarks

```bash
mvn package -pl irc24-bench -am -DskipTests
java -jar irc24-bench/target/benchmarks.jar -wi 3 -i 5 -f 1
```

## Package structure

```
irc24-lib/src/main/java/com/faradaym/irc24/
├── protocol/       IrcCommand, IrcReply, IrcMessages, MessageTooLongException
├── parser/         IrcMessageParser, IrcMessage
├── connection/     IrcConnection
└── client/         IrcClient, IrcClientConfig, IrcSession, IrcCommandSender, ReconnectStrategy
    └── handler/    IrcEventHandler, InternalHandler, IrcInternalHandlers, CircuitBreaker, NamesTracker

irc24-tui/src/main/java/com/faradaym/irc24/tui/
    IrcTui, TuiState, TuiRenderer, TuiMain

irc24-bench/src/main/java/com/faradaym/irc24/bench/
    ParserBenchmark, VirtualThreadsBenchmark
```

## Running tests

```bash
mvn test
# or only the library tests
mvn test -pl irc24-lib
```

Tests use a loopback `FakeServer` (real `ServerSocket`) — no external network required.
