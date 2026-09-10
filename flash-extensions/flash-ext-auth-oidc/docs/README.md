# flash-ext-auth-oidc

OpenID Connect for Flash: the authorization-code flow with PKCE, JWKS-validated bearer tokens,
server-side sessions with silent refresh, and single logout.

It is a **credential source** for [`flash-ext-auth-core`](../../flash-ext-auth-core/docs/README.md),
which owns everything downstream of "who is this caller" — `@Authenticated`, `@RolesAllowed`,
`@ScopesAllowed`, `ClaimsHolder`. Installing this extension installs that machinery too; you do not
install `flash-ext-auth-core` yourself.

## Quick start

```java
app.install(new OidcExtension(
        OidcConfig.builder(
                "https://keycloak.example.com/realms/myrealm",
                "my-app", "secret", "/auth/callback")
            .rolesClaimPath("realm_access.roles")
            .https()
            .build()));
```

That is the whole integration. Discovery runs at boot and fails fast if the issuer is unreachable,
so a misconfigured provider is a startup crash rather than a 500 on the first login.

`OidcConfig.fromEnv()` reads the same settings from `OIDC_*` environment variables, and
`OidcConfig.keycloak(serverUrl, realm, ...)` builds the issuer URL for you.

## What it registers

| | |
|---|---|
| `GET {prefix}/login` | Builds the authorization URL with PKCE + state and redirects |
| `GET {prefix}/callback` | Validates state and nonce, exchanges the code, creates the session |
| `POST {prefix}/logout` | Ends the session and redirects to the provider's end-session endpoint |

`{prefix}` is `routePrefix` (default `/auth`). Logout is a `POST` on purpose — a `GET` logout is
one `<img>` tag away from being triggered by any page the user visits.

In the context it provides `AuthMiddleware` (from auth-core), `OidcCredentialSource` and
`JwtValidator`.

## How a request is resolved

1. `Authorization: Bearer …` — validated against the issuer's JWKS.
2. `oidc_session` cookie — looked up in the `SessionStore`; if the access token has expired and a
   refresh token is present, refreshed transparently and the session replaced.
3. Neither, and the client sent `Accept: application/json` → `401` with a
   `WWW-Authenticate: Bearer` challenge.
4. Neither, and it looks like a browser → redirect to `{prefix}/login?redirect={path}`.

Points 3 and 4 are why the source distinguishes "no credential" from "bad credential": an API
client must not be redirected into an HTML sign-in page, and a browser must not be left staring at
a bare 401.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| `issuer`, `clientId`, `clientSecret`, `redirectUri` | — | required |
| `scopes` | `openid profile email` | |
| `routePrefix` | `/auth` | |
| `selfScheme` | `http` | `https()` behind TLS; only used when no `X-Forwarded-Proto` |
| `rolesClaimPath` | `realm_access.roles` | Keycloak's spelling; `groups` for Authelia |
| `scopeClaimPaths` | `scope,scp` | comma-separated, tried in order |
| `algorithm` | `RS256` | |
| `postLogoutRedirectUri` | `/` | |
| `sessionStore` | `InMemorySessionStore` | swap for Redis/JDBC across instances |
| `clientAuthMethod` | `POST` | token endpoint client authentication |
| `insecureTls()` | off | dev only, skips certificate validation |
| `schemeName` | derived from the issuer | OpenAPI security scheme name |

A relative `redirectUri` (starting with `/`) is resolved per request against the incoming `Host`,
or `X-Forwarded-Host`/`-Proto` when behind a proxy — so one build works in dev and behind TLS
without a second configuration.

## Sessions

A session holds the claims plus the access, id and refresh tokens, the last three in
`Session.attributes()` under this extension's own keys. Core never reads them; renewal happens
here. See [`../../flash-ext-auth-core/docs/sessions.md`](../../flash-ext-auth-core/docs/sessions.md).

## Multiple providers

Two issuers on one server, each with its own route prefix:

```java
app.install(new OidcExtension(tenantAConfig))   // routePrefix("/tenantA/auth")
   .install(new OidcExtension(tenantBConfig));  // routePrefix("/tenantB/auth")
```

Both are known at boot. Registering an issuer at runtime — a customer connecting their own IdP from
a settings page — is not supported.

## Interop

See [`interop.md`](interop.md) for how this extension fits with `flash-ext-auth-core`,
`flash-ext-openapi` and `flash-ext-mcp`.
