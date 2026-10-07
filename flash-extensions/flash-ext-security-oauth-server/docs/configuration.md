# Configuration

```java
new OAuthServer("/mcp")
        .store(myStore)
        .signingKeys(jwkSet)
        .subjects(identity -> new OAuthSubject(identity.principal().name(), Map.of("tenant", "acme")))
        .scopes("tools:call", "resources:read")
        .accessTokenLifetime(Duration.ofMinutes(10))
        .refreshTokenLifetime(Duration.ofDays(30))
        .consentPage("/consent")
        .registration(true);
```

| Method | Default | Meaning |
|---|---|---|
| `OAuthServer(resources...)` | required, at least one path starting with `/` | Paths tokens are issued for |
| `store(OAuthStore)` | `InMemoryOAuthStore` | Clients, grants and consents. Lost on restart |
| `signingKeys(String jwkSet)` | generated at boot | The first key must be a private P-256 key. Without it, tokens die with the process and a warning is logged |
| `subjects(Function<SecurityIdentity, OAuthSubject>)` | principal name, no claims | Who an authorization is for, and extra token claims. Throw to refuse a caller, for example a narrow credential like an API key |
| `scopes(String...)` | none | Scopes a client may be granted. Others requested are dropped |
| `accessTokenLifetime`, `refreshTokenLifetime` | 10 minutes, 30 days | |
| `consentPage(String)` | `/consent` | The application's consent page |
| `registration(boolean)` | `true` | Whether `/oauth/register` exists |
| `allowLocalClients()` | off | Client metadata documents on `http` or private addresses |

## Requirements

- `SecurityModule` installed, with `origin` set. The issuer is the origin.
- A `CacheManager` service (`flash-ext-cache-core`), used for client documents
  (`oauth-server.client-documents`) and the metadata document (`oauth-server.metadata`).
- A login page or entry point that signs a user in, and a consent page.

## Signing keys

```java
String jwkSet = OAuthServer.generateSigningKeys();   // generate once, keep secret
```

The result is a JWK set with one new private P-256 key. The first key signs and all keys verify, so
to rotate, put the new key first and remove the old one when the tokens it signed have expired.
`GET /oauth/jwks` publishes the public parts.

## OAuthStore

Implement it for persistence. `use(hash)` must be atomic: it marks a grant used and returns it as it
was before, so two concurrent uses cannot both see it unused.

| Method | Purpose |
|---|---|
| `client(id)`, `save(client)` | Registered clients |
| `save(grant)`, `find(hash)`, `use(hash)`, `revoke(family)` | Codes and refresh tokens, stored under the hash of their value |
| `consent(subject, clientId)`, `consent(subject, clientId, scope)` | The scope a user allowed a client |

`InMemoryOAuthStore` removes expired grants whenever a grant is saved.
