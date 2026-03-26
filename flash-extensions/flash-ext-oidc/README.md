# flash-ext-oidc

Full OIDC Authorization Code + PKCE flow for the Flash HTTP server.
Supports Keycloak, Authelia, Auth0, Google, and any RFC 8414-compliant provider.

## What it provides

| Component | Description |
|---|---|
| `GET  {prefix}/login` | Starts the OIDC flow: builds the authorization URL with PKCE + state, redirects |
| `GET  {prefix}/callback` | Exchanges the code, validates the ID token, creates a session, redirects |
| `POST {prefix}/logout` | Invalidates the session, redirects to the provider's `end_session_endpoint` |
| `@Authenticated` | Annotation: protects a class-based handler (redirects browsers, 401 for API clients) |
| `@RolesAllowed(...)` | Annotation: protects with role check (OR semantics) |
| `OidcMiddleware` | Programmatic middleware for lambda routes |
| `ClaimsHolder` / `OidcUser` | Thread-local user info accessible from any protected handler |
| `JwtValidator` | JWKS-backed JWT validator (PKCE + key rotation + caching) |

## Dependencies

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-oidc</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Transitive: `nimbus-jose-jwt`, `json-smart`.
Optional: `flash-ext-openapi` — if present, OIDC security schemes are added to the OpenAPI spec automatically.

## Installation

```java
FlashApp.of(new HttpServer(config))
    .install(new JacksonExtension())
    .install(new OpenApiExtension(...))   // optional — enables Swagger security
    .install(new OidcExtension(
        OidcConfig.builder(
                "https://idp.example.com",
                "my-client", "my-secret", "/auth/callback")
            .build()
    ));
```

### Keycloak shortcut

```java
OidcConfig.keycloak(
        "https://keycloak.example.com",   // server URL (no realm)
        "myrealm",                         // realm
        "my-client", "my-secret",          // client credentials
        "/auth/callback")                  // redirect URI (server-relative)
    .https()                               // behind TLS
    .build()
```

`keycloak()` pre-sets `rolesClaimPath("realm_access.roles")` and constructs the issuer as
`{serverUrl}/realms/{realm}`.

### Authelia / generic IdP

```java
OidcConfig.builder("https://auth.example.com", "my-client", "secret", "/auth/callback")
    .rolesClaimPath("groups")
    .build()
```

## OidcConfig reference

### Required fields

| Field | Description |
|---|---|
| `issuer` | Provider base URL — also used for OIDC discovery |
| `clientId` | OAuth2 client ID |
| `clientSecret` | OAuth2 client secret |
| `redirectUri` | Callback URI; server-relative paths (starting with `/`) are resolved at request time |

### Builder options

| Method | Default | Description |
|---|---|---|
| `.scopes("openid profile email")` | `"openid profile email"` | Space-separated requested scopes |
| `.routePrefix("/auth")` | `"/auth"` | Prefix for login/callback/logout routes |
| `.selfScheme("http")` | `"http"` | Scheme used when resolving server-relative redirect URIs |
| `.https()` | — | Shorthand for `.selfScheme("https")` |
| `.rolesClaimPath("realm_access.roles")` | `"realm_access.roles"` | Dot-path to the roles array in JWT claims |
| `.algorithm("RS256")` | `"RS256"` | JWS algorithm for token validation |
| `.postLogoutRedirectUri("/")` | `"/"` | Where to redirect after logout |
| `.sessionStore(store)` | `InMemoryOidcSessionStore` | Custom session store (see below) |
| `.clientAuthMethod(ClientAuthMethod.POST)` | `POST` | `POST` = credentials in body; `BASIC` = `Authorization: Basic` |
| `.insecureTls()` | `false` | Disables TLS certificate verification — **development only** |
| `.schemeName("myscheme")` | derived from issuer | OpenAPI security scheme name |

### Environment variables (`OidcConfig.fromEnv()`)

```
OIDC_ISSUER                required
OIDC_CLIENT_ID             required
OIDC_CLIENT_SECRET         required
OIDC_REDIRECT_URI          required   e.g. /auth/callback
OIDC_SCOPES                default: openid profile email
OIDC_ROUTE_PREFIX          default: /auth
OIDC_SELF_SCHEME           default: http
OIDC_ROLES_CLAIM           default: realm_access.roles
OIDC_ALGORITHM             default: RS256
OIDC_POST_LOGOUT_REDIRECT  default: /
OIDC_CLIENT_AUTH_METHOD    default: POST
```

## Protecting routes

### Class-based handlers (annotations)

```java
@Route(method = HttpMethod.GET, path = "/me")
@Authenticated
public class MePage extends JacksonHandler {
    @Override
    public Object handle(Request req, Response res) {
        OidcUser u = ClaimsHolder.user();
        return json(res, Map.of("sub", u.sub(), "email", u.email()));
    }
}

@Route(method = HttpMethod.GET, path = "/admin")
@RolesAllowed("admin")           // OR semantics: "admin" OR "superuser"
// @RolesAllowed({"admin", "superuser"})
public class AdminPage extends JacksonHandler { ... }
```

The middleware is injected automatically by the annotation processor — no manual wiring needed.

### Lambda routes (manual middleware)

For lambda routes you must apply the middleware explicitly. Retrieve it from the context
after `install()` completes:

```java
OidcMiddleware oidc = app.ctx().require(OidcMiddleware.class);

// Authentication only
app.get("/api/me", (req, res) -> {
    OidcUser u = ClaimsHolder.user();   // never null here
    return Map.of("sub", u.sub(), "email", u.email());
}, oidc.protect());

// Authentication + role check
app.delete("/api/admin/users/{id}", (req, res) -> {
    OidcUser u = ClaimsHolder.user();
    // ...
}, oidc.requireRole("admin"));

// Multiple roles (OR): passes if user holds any one of them
app.get("/api/reports", (req, res) -> { ... },
    oidc.requireRole("admin", "reports-viewer"));
```

`oidc.protect()` / `oidc.requireRole(...)` return a `Middleware` — a composable
`Handler -> Handler` wrapper. Flash applies middleware right-to-left so the OIDC check
runs before your handler.

## Accessing the authenticated user

`ClaimsHolder` holds the JWT claims for the current request in a `ThreadLocal`.
It is populated by the OIDC middleware before your handler runs and cleared in the
`finally` block afterward. It is safe with virtual threads (each request gets its
own virtual thread, so `ThreadLocal` values are naturally isolated).

### OidcUser (preferred)

```java
OidcUser u = ClaimsHolder.user();   // never null inside a protected handler

String sub      = u.sub();          // unique user ID
String email    = u.email();
String username = u.username();     // preferred_username
String name     = u.name();         // full display name

// Roles — pass the dot-path matching your provider's claim structure
List<String> roles = u.roles("realm_access.roles");   // Keycloak realm roles
List<String> clientRoles = u.roles("resource_access.my-client.roles"); // Keycloak client roles
List<String> groups = u.roles("groups");              // Authelia

boolean isAdmin = u.hasRole("realm_access.roles", "admin");

// Arbitrary claim
String locale = (String) u.claim("locale");
Long   exp    = u.claim("exp", Long.class);

// Full raw map (escape hatch)
Map<String, Object> all = u.claims();
```

### Raw access (escape hatch)

```java
Map<String, Object> claims = ClaimsHolder.get();
String email = ClaimsHolder.claim("email");
```

## Performance

The middleware adds negligible overhead on the hot path for authenticated requests:

| Step | Cost |
|---|---|
| `Authorization` header check | `O(1)` map lookup |
| Cookie parse | `O(cookie_count)` string split |
| Session lookup | `O(1)` `ConcurrentHashMap.get()` |
| Token expiry check | `O(1)` `Instant` comparison |
| `ClaimsHolder.set()` | `O(1)` `ThreadLocal.set()` |

No network calls, no cryptography, no JSON parsing on the happy path (valid session).
JWKS key fetching only happens for Bearer token validation and is cached + rate-limited by
Nimbus's `JWKSourceBuilder`. Silent token refresh only triggers when the access token expires.

## Authentication flow details

On each request the middleware resolves credentials in this order:

1. **Bearer token** (`Authorization: Bearer <jwt>`) — validated against JWKS.
2. **Session cookie** (`oidc_session`) — looked up in the session store; transparently
   refreshed if the access token is expired (silent refresh via refresh token).
3. **No valid credentials**:
   - Browser clients (no `Accept: application/json`) → redirect to `{prefix}/login?redirect={path}`
   - API clients → `401 Unauthorized`

### Token validation (OIDC Core §3.1.3.7)

| Check | Access token | ID token |
|---|---|---|
| Signature (JWKS) | yes | yes |
| `iss` | yes | yes |
| `aud` = clientId | no (varies by provider) | yes |
| `exp`, `iat`, `sub` | yes | yes |
| `nonce` | — | yes |

JWKS keys are cached, rate-limited, and retried on cache-miss (handles key rotation).

### Claim merge strategy

At callback time the extension merges access token + ID token claims:

- Access token claims first (contains provider-specific data like `realm_access.roles`)
- ID token claims override (contains verified identity: `sub`, `email`, `name`, …)

This is provider-agnostic: authorization claims live in the AT per RFC 9068,
identity claims live in the IT per OIDC Core.

## Session store

The default `InMemoryOidcSessionStore` is sufficient for single-instance deployments.
For clustered deployments, implement `OidcSessionStore`:

```java
public interface OidcSessionStore {
    void              save(OidcSession session);
    Optional<OidcSession> find(String sessionId);
    void              delete(String sessionId);
}
```

```java
OidcConfig.builder(...)
    .sessionStore(new RedisOidcSessionStore(redisClient))
    .build()
```

`OidcSession` fields: `id`, `accessToken`, `idToken`, `refreshToken`, `expiresAt` (`Instant`), `claims` (merged map).

## Logout

Add a logout button anywhere in your UI — a `<form>` is sufficient (no JavaScript needed):

```html
<form method="POST" action="/auth/logout">
    <button type="submit">Logout</button>
</form>
```

The `POST {prefix}/logout` handler:
1. Reads the `oidc_session` cookie, looks up the session, retrieves the `id_token`.
2. Deletes the local session and clears the cookie (`Max-Age=0`).
3. If the provider has an `end_session_endpoint` (standard IdPs do), redirects there with
   `?id_token_hint=<idToken>&post_logout_redirect_uri=<postLogoutRedirectUri>` — this logs
   the user out of the IdP as well.
4. Otherwise redirects to `postLogoutRedirectUri` (default: `/`).

## Bearer token (API clients)

For API-to-API or SPA-to-API calls, pass a Bearer access token directly. The middleware
validates the JWT signature against JWKS and extracts the claims — no session involved:

```
Authorization: Bearer <access_token>
```

The token must be a JWT (opaque tokens are not supported). Claims are available via
`ClaimsHolder.user()` as usual.

## Multi-tenant

Multiple OIDC providers on one server — each `OidcExtension` instance is fully independent
(its own PKCE state store, session store, validator, and middleware):

```java
OidcConfig tenantA = OidcConfig.builder("https://idp/realms/a", "clientA", "secretA", "/a/auth/callback")
    .routePrefix("/a/auth").schemeName("tenantA").build();

OidcConfig tenantB = OidcConfig.builder("https://idp/realms/b", "clientB", "secretB", "/b/auth/callback")
    .routePrefix("/b/auth").schemeName("tenantB").build();

app.install(new OidcExtension(tenantA))
   .install(new OidcExtension(tenantB));
```

To reference a specific tenant's middleware on lambda routes, keep the extension reference
and retrieve `OidcMiddleware` from context **after** each install:

```java
app.install(new OidcExtension(tenantA));
OidcMiddleware mwA = app.ctx().require(OidcMiddleware.class);   // tenantA's middleware

app.install(new OidcExtension(tenantB));
OidcMiddleware mwB = app.ctx().require(OidcMiddleware.class);   // tenantB's middleware

app.get("/a/dashboard", (req, res) -> { ... }, mwA.protect());
app.get("/b/dashboard", (req, res) -> { ... }, mwB.protect());
```

Class-based handlers annotated with `@Authenticated` / `@RolesAllowed` get the last
registered middleware injected. For multi-tenant class-based handlers, use lambdas or
install tenant-specific annotation processors.

## OpenAPI integration

If `flash-ext-openapi` is on the classpath and installed **before** `flash-ext-oidc`,
the extension automatically:

- Adds a `components.securitySchemes` entry for the provider (OAuth2, authorizationCode flow)
- Adds `security` requirements to every operation whose handler carries `@Authenticated`
  or `@RolesAllowed`

No extra code needed. To customize the scheme name:

```java
OidcConfig.builder(...).schemeName("keycloak").build()
```

If `flash-ext-openapi` is absent the integration is silently skipped.
