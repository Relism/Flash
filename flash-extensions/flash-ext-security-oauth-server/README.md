# flash-ext-security-oauth-server

An OAuth 2.1 authorization server inside the application. Users sign in the way the application
already signs people in, consent on the application's own page, and clients receive access tokens for
the application's resources. It also authenticates those tokens. It is built for protecting
resources such as an MCP endpoint, where clients register themselves.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-oauth-server</artifactId>
</dependency>
```

```java
app.install(
        new SecurityModule().origin("https://app.example").loginPage("/login"),
        new CaffeineCacheModule(),              // a CacheManager is required
        new OAuthServer("/mcp").signingKeys(keys).scopes("tools:call"));
```

| Page | Content |
|---|---|
| [docs/flows.md](docs/flows.md) | Endpoints, grants, consent, token format |
| [docs/clients.md](docs/clients.md) | Registration, metadata documents, redirect URIs |
| [docs/configuration.md](docs/configuration.md) | Options, keys, stores |
