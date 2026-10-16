# flash-testing

A JUnit 5 harness that runs a real `App` on a loopback port and gives you an HTTP client for it. The
app is the one production builds. A fake replaces exactly one service.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-testing</artifactId>
    <scope>test</scope>
</dependency>
```

```java
class UserRoutesTest {

    @RegisterExtension
    static FlashTest api = FlashTest.of(() -> Main.api(testDatabase()))
            .replace(Mailer.class, new FakeMailer());

    @Test
    void listsUsers() {
        api.get("/api/users").expectStatus(200).expectBodyContains("alice");
    }
}
```

| Page | Content |
|---|---|
| [docs/harness.md](docs/harness.md) | `FlashTest`: lifecycle, configuration, replacing services |
| [docs/requests.md](docs/requests.md) | `FlashRequest` and `FlashResponse` |
| [docs/websocket.md](docs/websocket.md) | `FlashWebSocket` |
| [docs/limits.md](docs/limits.md) | What the harness cannot do |
