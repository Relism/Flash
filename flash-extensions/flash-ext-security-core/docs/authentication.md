# Authentication

## Mechanisms

An `AuthenticationMechanism` reads one kind of credential off a request:

```java
public interface AuthenticationMechanism {
    Principal authenticate(Request req);
    default List<SecurityScheme> schemes() { return List.of(); }
}
```

Return `null` when the request carries no credential of your kind. Throw
`AuthenticationFailedException` when it carries one that is invalid. A mechanism never writes the
response.

Register one with `app.add(AuthenticationMechanism.class, mechanism)`. Modules do this for you.

## The chain

For each request the chain runs in the order mechanisms were added:

1. The first mechanism that returns a principal wins.
2. If none does, the session cookie is checked.
3. If that finds nothing the caller is anonymous.

A mechanism that throws stops the chain. For `@PermitAll` handlers the failure is ignored and the
caller is anonymous; for others the `401` is answered.

## Principals

```java
public interface Principal {
    String name();                              // unique within the mechanism
    default boolean hasScope(String scope)    { return true; }
    default boolean hasAudience(String a)     { return true; }
    default String logoutUrl()                { return null; }
}
```

Each mechanism has its own principal type: `OidcPrincipal`, `ApiKeyPrincipal`, `OAuthPrincipal`.
Read it back with `identity.principal(OidcPrincipal.class)`.

## Failures

`AuthenticationFailedException(challenge)` is a stackless `HttpException` with status `401`. A
non-null `challenge` becomes the `WWW-Authenticate` header, for example `Bearer error="invalid_token"`.
`AuthenticationFailedException.invalidToken()` is that one. Throw a new instance each time: an
`HttpException` collects the headers a middleware adds to it, so a shared one would keep them all.

## Schemes

`SecurityScheme` describes a credential for OpenAPI and for `401` challenges:

| Factory | Definition | Challenge |
|---|---|---|
| `SecurityScheme.bearer(name, bearerFormat)` | HTTP bearer | `Bearer realm="<name>"` |
| `SecurityScheme.openIdConnect(name, issuer)` | OpenID Connect with the discovery URL | `Bearer realm="<name>"` |

The record also holds the raw OpenAPI definition map and the `issuer`. `"/"` as issuer means the
application is its own authorization server. A `401` without credentials lists every challenge of
every scheme, joined by `, `.

## Login methods

A `LoginMethod(id, name, url, kind)` is a way to sign in that `GET /auth/methods` lists for a client.
`kind` is `REDIRECT` (the browser navigates to `url` and returns signed in) or `FORM` (a client posts
`username` and `password` to `url`). Modules register them as services.

## Entry points

An `AuthenticationEntryPoint` answers a request that needs a caller and has no credential. Set one
globally with `SecurityModule.entryPoint`, or per route with `enforce(policy, entryPoint)`.
