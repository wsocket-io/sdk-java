# wSocket Java SDK

Official Java SDK for [wSocket](https://wsocket.io) — Realtime Pub/Sub over WebSockets.

[![Maven Central](https://img.shields.io/maven-central/v/io.wsocket/wsocket-io)](https://central.sonatype.com/artifact/io.wsocket/wsocket-io)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Installation

### Maven

```xml
<dependency>
    <groupId>io.wsocket</groupId>
    <artifactId>wsocket-io</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.wsocket:wsocket-io:0.1.0'
```

## Quick Start

```java
import io.wsocket.sdk.Client;
import io.wsocket.sdk.Client.Channel;

public class Main {
    public static void main(String[] args) throws Exception {
        Client client = new Client("wss://node00.wsocket.online", "your-api-key");
        client.connect();

        Channel chat = client.channel("chat:general");
        chat.subscribe((data, meta) -> {
            System.out.println("[" + meta.channel + "] " + data);
        });

        chat.publish(Map.of("text", "Hello from Java!"));

        Thread.sleep(Long.MAX_VALUE); // keep alive
    }
}
```

## Features

- **Pub/Sub** — Subscribe and publish to channels in real-time
- **Presence** — Track who is online in a channel
- **History** — Retrieve past messages
- **Connection Recovery** — Automatic reconnection with message replay
- **Thread-safe** — Safe for concurrent use

## Presence

```java
Channel chat = client.channel("chat:general");

chat.presence().onEnter(m -> System.out.println("joined: " + m.clientId));
chat.presence().onLeave(m -> System.out.println("left: " + m.clientId));
chat.presence().enter(Map.of("name", "Alice"));

List<PresenceMember> members = chat.presence().get();
```

## History

```java
chat.onHistory(result -> {
    result.messages.forEach(msg ->
        System.out.println("[" + msg.timestamp + "] " + msg.data)
    );
});

chat.history(new HistoryOptions().limit(50));
```

## Requirements

- Java >= 17
- OkHttp 4.12+
- Gson 2.11+

## Development

```bash
mvn clean package
mvn test
```

## License

MIT
