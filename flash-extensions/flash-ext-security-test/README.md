# flash-ext-security-test

Test helpers for applications that use `flash-ext-security-*`. They run on `flash-testing`, which
serves the app in the test's own JVM.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

```java
static final TestSecurity users = new TestSecurity();
@RegisterExtension static FlashTest app = FlashTest.of(() -> Main.api(db).install(users));

@Test void me() {
    app.request().with(users.as(() -> "alice")).get("/me").expectStatus(200);
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | `TestSecurity`, `FakeOidcProvider`, `OAuthTestClient`, `OidcTokens` |
