# Providers

```java
OidcProvider provider = OidcProvider.of("sso", issuer, clientId, clientSecret)
        .name("Company SSO")
        .scopes("openid profile email");
```

| Field | Meaning |
|---|---|
| `id` | Letters, digits, `_` and `-`. Appears in routes (`/auth/oidc/{id}/login`), OpenAPI and principals |
| `name` | What a login page shows. Default: the id |
| `issuer` | Exactly as the provider publishes it. Some end in `/` |
| `clientId`, `clientSecret` | The client the app is registered as. The secret is sent with `client_secret_basic` |
| `scopes` | Requested at sign-in. Default `openid profile email` |

## Discovery

When the server starts, every configured provider is discovered at
`<issuer>/.well-known/openid-configuration` with timeouts of 5 seconds to connect and 10 seconds per
request. A provider that cannot be reached, or whose document lacks `issuer`,
`authorization_endpoint` or `token_endpoint`, fails the boot and names the provider. Signing keys
are read from `jwks_uri` through Nimbus's JWK source, with retry.

## Providers at runtime

For an application where each customer connects their own identity provider:

```java
oidc.register(OidcProvider.of("acme", "https://id.acme.com", clientId, secret));
oidc.verify(provider, "https://app.example");   // checks credentials and redirect URI, registers nothing
oidc.unregister("acme");
```

`Oidc` is the service installed by `OidcModule`; take it as a constructor parameter, or read
`oidcModule.oidc()` before start.

| Method | Behavior |
|---|---|
| `register(provider)` | Discovers and trusts it. Fails with `IllegalArgumentException` if the id belongs to a configured provider, or if the provider or an endpoint it names is not `https` on a public address |
| `verify(provider, origin)` | Checks that the provider accepts the client credentials (a token request with a made-up code must not answer 401 or `invalid_client`) and the redirect URI a sign-in from `origin` sends. Registers nothing |
| `unregister(id)` | Its tokens stop authenticating and its sessions stop renewing |

A registered provider serves bearer tokens and `/auth/oidc/{id}/login`, but is not listed at
`/auth/methods`: which provider a user signs in with is the application's decision.

`new OidcModule(...).allowLocalProviders()` lets runtime providers use `http` and private or
loopback addresses. Use it in development only: otherwise registering a provider would be a
request forgery against the server's own network.
