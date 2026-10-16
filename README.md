# Flash

[![JitPack](https://jitpack.io/v/Relism/Flash.svg)](https://jitpack.io/#Relism/Flash)

An HTTP/1.1 and HTTP/2 server and routing library for Java 21. It runs one virtual thread per
connection and uses the same `Request`, `Response` and handler types on both protocols.

```java
App app = App.create()
        .get("/hello/{name}", (req, res) -> "Hello " + req.param("name"));

Server.start(app, ServerConfig.builder().port(8080).build()).await();
```

An `App` holds routes, middleware, modules and services and knows nothing about ports. A `Server`
runs it. Tests run the same `App` on a port of their own.

## Requirements

- Java 21 or later
- Maven 3.8 or later

## Installation

Releases are published on [JitPack](https://jitpack.io/#Relism/Flash). Every module has the
groupId `dev.relism.Flash` and the release version as its version, which is the tag without its
`v`, shown in the badge above. Import
`flash-parent` once and declare modules without a version:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.relism.Flash</groupId>
            <artifactId>flash-parent</artifactId>
            <version>X.Y.Z</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.relism.Flash</groupId>
        <artifactId>flash</artifactId>
    </dependency>
</dependencies>
```

## Documentation

| | |
|---|---|
| [Core](flash/docs/README.md) | Application model, routing, requests and responses, WebSocket, SSE, configuration, TLS, HTTP/1.1, HTTP/2 |
| [Testing](flash-testing/README.md) | JUnit 5 harness |
| Extensions | One README per module, listed below, each linking to its own `docs` directory |

## Modules

| Module | Purpose |
|---|---|
| [flash](flash/docs/README.md) | The server library |
| [flash-testing](flash-testing/README.md) | Test harness: run an app on a loopback port, replace services, assert on responses |

### Formats and validation

| Module | Purpose |
|---|---|
| [flash-ext-serde](flash-extensions/flash-ext-serde/README.md) | Codec contract and typed handler bases |
| [flash-ext-jackson-core](flash-extensions/flash-ext-jackson-core/README.md) | Shared base of the Jackson formats |
| [flash-ext-jackson-json](flash-extensions/flash-ext-jackson-json/README.md) | JSON through Jackson |
| [flash-ext-jackson-xml](flash-extensions/flash-ext-jackson-xml/README.md) | XML through Jackson |
| [flash-ext-avaje-jsonb](flash-extensions/flash-ext-avaje-jsonb/README.md) | JSON through Avaje Jsonb, adapters generated at build time |
| [flash-ext-protobuf-java](flash-extensions/flash-ext-protobuf-java/README.md) | Protocol Buffers bodies |
| [flash-ext-grpc](flash-extensions/flash-ext-grpc/README.md) | gRPC over HTTP/2, gRPC-Web over HTTP/1.1 and HTTP/2 |
| [flash-ext-ndjson](flash-extensions/flash-ext-ndjson/README.md) | Newline-delimited JSON streams, in and out |
| [flash-ext-validation-core](flash-extensions/flash-ext-validation-core/README.md) | Validation contract |
| [flash-ext-validation-jakarta](flash-extensions/flash-ext-validation-jakarta/README.md) | Jakarta Validation through Hibernate Validator |
| [flash-ext-validation-avaje](flash-extensions/flash-ext-validation-avaje/README.md) | Avaje Validator |
| [flash-ext-validation-protovalidate](flash-extensions/flash-ext-validation-protovalidate/README.md) | Protovalidate for protobuf messages |
| [flash-ext-openapi](flash-extensions/flash-ext-openapi/README.md) | OpenAPI 3.0.3 document and documentation page |

### Security

| Module | Purpose |
|---|---|
| [flash-ext-security-core](flash-extensions/flash-ext-security-core/README.md) | Authentication chain, annotations, sessions |
| [flash-ext-security-oidc](flash-extensions/flash-ext-security-oidc/README.md) | OpenID Connect |
| [flash-ext-security-apikey](flash-extensions/flash-ext-security-apikey/README.md) | API keys |
| [flash-ext-security-form](flash-extensions/flash-ext-security-form/README.md) | Password sign-in |
| [flash-ext-security-basic](flash-extensions/flash-ext-security-basic/README.md) | HTTP Basic over the password sign-in accounts |
| [flash-ext-security-oauth-server](flash-extensions/flash-ext-security-oauth-server/README.md) | OAuth 2.1 authorization server |
| [flash-ext-security-test](flash-extensions/flash-ext-security-test/README.md) | Test helpers for security |
| [flash-ext-limiter](flash-extensions/flash-ext-limiter/README.md) | Rate limiting |

### Application building blocks

| Module | Purpose |
|---|---|
| [flash-ext-view-core](flash-extensions/flash-ext-view-core/README.md) | Shared template handler and model |
| [flash-ext-view-jte](flash-extensions/flash-ext-view-jte/README.md) | jte templates |
| [flash-ext-view-thymeleaf](flash-extensions/flash-ext-view-thymeleaf/README.md) | Thymeleaf templates |
| [flash-ext-vite](flash-extensions/flash-ext-vite/README.md) | Vite frontend |
| [flash-ext-vite-maven-plugin](flash-extensions/flash-ext-vite-maven-plugin/README.md) | Builds the Vite frontend into the jar |
| [flash-ext-mcp](flash-extensions/flash-ext-mcp/README.md) | Model Context Protocol server |
| [flash-ext-webdav](flash-extensions/flash-ext-webdav/README.md) | WebDAV file shares with per-request spaces and quotas, over a directory or an engine of your own |
| [flash-ext-scheduler](flash-extensions/flash-ext-scheduler/README.md) | Interval and cron jobs |
| [flash-ext-cache-core](flash-extensions/flash-ext-cache-core/README.md) | Cache contract |
| [flash-ext-cache-caffeine](flash-extensions/flash-ext-cache-caffeine/README.md) | In-process cache with Caffeine |
| [flash-ext-data-core](flash-extensions/flash-ext-data-core/README.md) | Transactions and repositories |
| [flash-ext-data-jdbc](flash-extensions/flash-ext-data-jdbc/README.md) | JDBC backend |
| [flash-ext-data-hibernate](flash-extensions/flash-ext-data-hibernate/README.md) | Hibernate backend |
| [flash-ext-micrometer](flash-extensions/flash-ext-micrometer/README.md) | HTTP server metrics |
| [flash-ext-micrometer-prometheus](flash-extensions/flash-ext-micrometer-prometheus/README.md) | Prometheus endpoint |

## Build

```bash
./mvnw clean package -DskipTests      # build every module
./mvnw test                           # run the tests, without those against real clients
./mvnw test -pl flash -Dtest=AppTest  # one test class in one module
./mvnw test -Pinterop                 # only the tests against real clients (Node, Docker)
```
