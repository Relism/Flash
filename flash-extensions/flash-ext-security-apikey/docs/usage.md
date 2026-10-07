# Using API keys

## Key format

```
gk_<id>.<secret>
 │   │      └ 192 random bits
 │   └ 72 random bits
 └ prefix you choose
```

The prefix identifies your keys at a glance and to secret scanners. It must match `[A-Za-z0-9]+`,
otherwise the constructor throws. The id is what the store is queried by. Id and secret are random,
base64url, from `Tokens.random`.

## Generating

```java
GeneratedApiKey key = apiKeys.generate();
// key.token()       shown to the caller once
// key.id()          store this
// key.secretHash()  store this
```

`ApiKeys` is registered as a service, so a handler takes `ApiKeys<?>` as a constructor parameter.
The token is never stored.

## The store

```java
@FunctionalInterface
public interface ApiKeyStore<G> {
    ApiKey<G> find(String id);
}

public record ApiKey<G>(String id, String secretHash, G grant, Instant expiresAt, Instant revokedAt) {}
```

`grant` is whatever your application authorizes on (a team, a scope list). `expiresAt` and
`revokedAt` are `null` when they do not apply. A key is active when it has not been revoked and has
not expired.

## Authentication

A request with `Authorization: Bearer gk_<id>.<secret>` is checked as follows. A header that does not
start with `Bearer <prefix>_` is not this mechanism's and returns `null`, so other bearer
mechanisms still run.

- No `.` after the id, an unknown id, a wrong secret or an inactive key all give `401` with
  `Bearer error="invalid_token"`. The secret is compared in constant time against the stored hash.
- On success the principal is `ApiKeyPrincipal<G>(name = key id, grant)`. It grants every scope and
  audience, because an API key carries none.

```java
@Authenticated
final class Export extends RequestHandler {
    @Override protected Object handle(Request req, Response res) {
        var principal = SecurityIdentity.current().principal(ApiKeyPrincipal.class);
        ...
    }
}
```

## OpenAPI

Documented as an HTTP bearer scheme named `apiKey` with bearer format `<prefix>_<id>.<secret>`.
