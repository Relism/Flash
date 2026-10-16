# flash-ext-security-core

Authentication and authorization for Flash. It defines how a caller is identified, what a handler's
annotations require, and where sessions live. Credentials are read by
`AuthenticationMechanism`s, which other modules provide (OIDC, API keys, form login, HTTP Basic,
an OAuth authorization server).

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-core</artifactId>
</dependency>
```

```java
app.install(new SecurityModule().origin("https://app.example").roles(roles),
            new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)))
   .scan("com.acme.api");

@GET("/projects/{project}")
@RolesAllowed(value = "editor", on = "project")
final class GetProject extends RequestHandler { ... }
```

`SecurityModule` also serves `POST /auth/logout` and `GET /auth/methods`.

| Page | Content |
|---|---|
| [docs/module.md](docs/module.md) | `SecurityModule` options, the routes it adds |
| [docs/authorization.md](docs/authorization.md) | Annotations, roles, scopes, `SecurityIdentity` |
| [docs/authentication.md](docs/authentication.md) | Mechanisms, the chain, failures, entry points |
| [docs/sessions.md](docs/sessions.md) | Session cookie, stores, refreshing |
| [docs/helpers.md](docs/helpers.md) | `Tokens`, `Redirects`, `PublicUrl` |
