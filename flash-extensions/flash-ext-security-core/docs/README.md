# flash-ext-security-core

Authentication and authorization for Flash, independent of any credential. Mechanisms —
[`-oidc`](../../flash-ext-security-oidc/docs/README.md), [`-apikey`](../../flash-ext-security-apikey/docs/README.md),
[`-form`](../../flash-ext-security-form/docs/README.md), or your own — register into one chain;
this module owns everything downstream of "who is the caller".

```java
app.install(new SecurityModule()
        .users(principal -> users.findOrProvision(principal))   // optional: principal → your user
        .roles((identity, role, on) -> members.has(identity.user(User.class), role, on.get("project"))),
    new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)));
```

## The model

| Type | Role |
|---|---|
| `AuthenticationMechanism` | reads one kind of credential: returns a `Principal`, `null` (not mine), or throws `AuthenticationFailedException` (mine, invalid) |
| `Principal` | who the mechanism proved the caller to be — typed per mechanism (`OidcPrincipal`, `ApiKeyPrincipal`, …) |
| `SecurityIdentity` | the current caller: `principal(OidcPrincipal.class)`, `user(User.class)`, `hasRole`, `hasScope` |
| `UserResolver` | principal → application user, resolved lazily, once per request |
| `RoleResolver` | whether a caller holds a role, optionally on a resource |
| `AuthenticationEntryPoint` | the answer to a request that needs a caller and carries no credential |

Mechanisms never write the response. That is what keeps the one mistake that matters impossible to
make: a credential that was presented and rejected is always a 401, never a redirect into a sign-in
page an API client cannot parse.

## Annotations

On a handler or an MCP tool class:

| | |
|---|---|
| `@Authenticated` | any authenticated caller |
| `@PermitAll` | anyone; a caller who authenticates is still identified, one who fails is anonymous |
| `@RolesAllowed(value, on)` | any of the roles; `on` names the path/query parameters (tool arguments on MCP) identifying the resource |
| `@ScopesAllowed(value)` | every one of the credential's scopes |

`@RolesAllowed(value = "MANAGER", on = "project")` on `/projects/{project}/keys` asks the
`RoleResolver` whether the caller is a manager *of that project*. A handler declaring roles with no
`RoleResolver` configured fails the boot. Policies compile once; checking one allocates nothing.

## The chain

Mechanisms are tried in registration order, then the session cookie. The first to return a
principal wins. When none does:

- a browser (`Accept: text/html`) is redirected to `loginPage` when one is set, otherwise to the only
  login method when it is a redirect, otherwise to `/login`;
- anything else gets `401` with every mechanism's challenge in `WWW-Authenticate`.

`entryPoint(...)` replaces that, e.g. to pick an identity provider from the user's email domain.

`enforce(policy, entryPoint, mechanisms)` restricts a route to some mechanisms: any other credential,
the session cookie included, is no credential there — a bearer-only endpoint a browser's cookie must
not reach.

## Origin

`origin("https://app.example")` is where the application is served. Session cookies, sign-in
callbacks and token audiences are built from `origin(req)`: the configured origin, or the request's
own scheme and `Host` when none is. `X-Forwarded-*` is never read: the client can send anything, and
an origin taken from it lets a caller choose which audience a token must have. Configure it in every
deployment; the fallback is for development and tests.

## Addresses someone else chose

`PublicUrl.require(url)` refuses anything that is not https on a public address. Call it before the
server fetches a URL a customer or a client supplied — an identity provider, a metadata document — or
that fetch becomes a request forgery against the server's own network.

## Sessions

`signIn(req, res, principal[, expiresAt])` stores the principal under a `flash_session` cookie;
`POST /auth/logout` ends it and follows `Principal.logoutUrl()`. An expired session is handed to the
`SessionRefresher` registered for its principal type, or ended. `InMemorySessionStore` is the
default; `sessions(...)` swaps it for one that survives a restart or spans instances.

`GET /auth/methods` lists every registered `LoginMethod` for a client to render.

## OpenAPI

With `flash-ext-openapi` present, every registered mechanism's `SecurityScheme` is published, and
every protected operation lists them as alternatives, with its 401 and — for roles or scopes — its
403 and what it requires. Nothing to write per mechanism.

## Writing a mechanism

```java
security.mechanism(new AuthenticationMechanism() {
    public Principal authenticate(Request req) {
        String key = req.header("X-Key");
        if (key == null) return null;                                  // not mine
        Principal p = keys.get(key);
        if (p == null) throw new AuthenticationFailedException(null);  // mine, and invalid
        return p;
    }
    public List<SecurityScheme> schemes() { return List.of(SecurityScheme.bearer("key", "opaque")); }
});
```

## Testing

[`flash-ext-security-test`](../../flash-ext-security-test/docs/README.md) authenticates requests as
any principal without an identity provider.
