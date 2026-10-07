# flash-ext-security-oidc

OpenID Connect for Flash: bearer access tokens from any number of providers, and browser sign-in
with the authorization code flow and PKCE. It needs `flash-ext-security-core`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-oidc</artifactId>
</dependency>
```

```java
app.install(
        new SecurityModule().origin("https://app.example"),
        new OidcModule(OidcProvider.of("sso", "https://id.example.com/realms/acme", "app", secret)));
```

| Page | Content |
|---|---|
| [docs/sign-in.md](docs/sign-in.md) | Browser sign-in, routes, sessions, refresh |
| [docs/tokens.md](docs/tokens.md) | Bearer tokens, scopes, audience, roles from claims |
| [docs/providers.md](docs/providers.md) | Configuring and registering providers at runtime |
