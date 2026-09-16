# flash-ext-security-core

Authentication and authorization for Flash, independent of any credential. Mechanisms —
[`-oidc`](../../flash-ext-security-oidc/docs/README.md), [`-apikey`](../../flash-ext-security-apikey/docs/README.md),
[`-form`](../../flash-ext-security-form/docs/README.md), or your own — register into one chain;
this module owns everything downstream of "who is the caller".

```java
app.install(new SecurityExtension()
        .users(principal -> users.findOrProvision(principal))   // optional: principal → your user
        .roles((identity, role, on) -> members.has(identity.user(User.class), role, on.get("project"))))
   .install(new OidcExtension(OidcProvider.of("sso", issuer, clientId, secret)));
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

- a browser (`Accept: text/html`) is redirected to the only login method, or to `loginPage` when there are several;
- anything else gets `401` with every mechanism's challenge in `WWW-Authenticate`.

`entryPoint(...)` replaces that, e.g. to pick an identity provider from the user's email domain.

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
    public SecurityScheme scheme() { return SecurityScheme.bearer("key", "opaque"); }
});
```

## Testing

[`flash-ext-security-test`](../../flash-ext-security-test/docs/README.md) authenticates requests as
any principal without an identity provider.
