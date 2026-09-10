# flash-ext-auth-core

Authorization, and the plumbing that carries a caller's identity through a request. It does not
know how anyone signed in — that is a `CredentialSource`, and `flash-ext-auth-oidc` ships the
OpenID Connect one.

The split follows the same shape as `flash-ext-cache-core`/`-caffeine` and
`flash-ext-data-core`/`-hibernate`: the abstract half here, the implementations beside it.

## The model

```
request ──► CredentialSource.authenticate(req, res) ──► claims
                                                          │
                                            ClaimsHolder.set (this module only)
                                                          │
                                           AuthMiddleware matches roles / scopes
                                                          │
                                                       handler
```

| Type | What it is |
|---|---|
| `CredentialSource` | Turns what a request carries into claims, or rejects it. One per mechanism. |
| `AuthMiddleware` | Publishes the claims, enforces `@RolesAllowed`/`@ScopesAllowed`, clears up. |
| `ClaimsHolder` | The current request's claims. Read from anywhere; written only from here. |
| `Claims` | Typed view over a claims map — `sub()`, `email()`, `roles(path)`, `scopes()`. |
| `AuthPolicy` | What a handler's annotations compiled to, resolved once at boot. |
| `Session`, `SessionStore` | Server-side sessions for sources that keep them. |

Nothing outside this module can write `ClaimsHolder`. A source *returns* claims and the middleware
publishes them, so no code can put claims on a request that did not carry them.

## Using it

You rarely install this module directly — an extension that contributes a source does it for you:

```java
// inside your extension's configure(...)
AuthMiddleware auth = AuthMiddleware.install(ctx, AuthConfig.builder()
        .rolesClaimPath("realm_access.roles")
        .scopeClaimPaths("scope,scp")
        .build(), mySource);
```

`install` publishes the middleware in the context and registers the annotation processor, so every
scanned handler carrying an auth annotation is mounted behind it. See
[`credential-sources.md`](credential-sources.md) to write a source of your own.

On lambda routes, take the middleware out of the context:

```java
AuthMiddleware auth = app.ctx().require(AuthMiddleware.class);

app.get("/api/me",            (req, res) -> ClaimsHolder.claim("sub"), auth.protect());
app.get("/",                  homeHandler,                            auth.optional());
app.delete("/admin/users/{id}", deleteHandler,                        auth.requireRole("admin"));
app.post("/orders",           createOrder,       auth.requireScopes("orders:write"));
```

## Annotations

On a scanned handler class, and mounted automatically:

| Annotation | Effect |
|---|---|
| `@Authenticated` | Any accepted credential. No role check. |
| `@Authenticated(optional = true)` | Never rejects; publishes claims when there are some. |
| `@RolesAllowed({"a","b"})` | Authenticated **and** holding at least one of the roles. |
| `@ScopesAllowed({"x","y"})` | Authenticated **and** holding all of the scopes. |
| `@ScopesAllowed(value = {...}, match = ANY)` | …at least one of them. |

`@Authenticated(optional = true)` cannot be combined with a role or scope requirement — asking for
a role on a route that admits anonymous callers is a contradiction, and it fails at boot rather
than at 3am.

## Where roles and scopes are read from

`AuthConfig` names the claim paths, because every provider spells them differently:

| | Default | Common alternatives |
|---|---|---|
| `rolesClaimPath` | `roles` | `realm_access.roles` (Keycloak), `groups` (Authelia) |
| `scopeClaimPaths` | `scope,scp` | plus e.g. `permissions.scopes` |

Paths are dot-separated and walk nested maps. Scope paths are a comma-separated list tried in
order, so a token that puts scopes in `scp` and a legacy one that uses `scope` both work.

Matching is deliberate about a distinction that bites otherwise:

- a **string** claim is split on spaces, tabs, newlines and commas — `"openid orders:read"` is two
  scopes;
- a **list** claim is compared entry by entry, whole and trimmed — `["a b"]` is one role named
  `a b`, not two.

Prefix matches never count: `administrator` does not satisfy `admin`.

## Ordering around authentication

`AuthMiddleware.POLICY` is the boot-time key the annotation-driven node mounts under. An extension
contributing its own middleware can order itself against it:

```java
MiddlewareNode.of(MY_KEY, myMiddleware).afterIfPresent(AuthMiddleware.POLICY);
```
