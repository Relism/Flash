# flash-ext-security-oidc

OpenID Connect for [`flash-ext-security-core`](../../flash-ext-security-core/docs/README.md): bearer
access tokens, and browser sign-in through the authorization code flow with PKCE.

```java
app.install(new SecurityExtension())
   .install(new OidcExtension(
           OidcProvider.of("sso", "https://id.example.com/realms/acme", "app", secret).name("Acme SSO"),
           OidcProvider.of("partner", "https://login.partner.example/", "app", partnerSecret)));
```

Discovery runs at boot, so an unreachable provider fails the start rather than the first sign-in.

## Bearer tokens

`Authorization: Bearer <jwt>` is matched to its provider by `iss`, then verified against that
provider's keys (RS/PS/ES algorithms, `typ` `JWT` or `at+jwt`, `iss`, `sub`, `exp`). One parse, one map
lookup, however many providers are configured. A token from an unconfigured issuer is left to other
mechanisms; a token from a configured one that fails verification is `401 invalid_token`.

## Sign-in

| Route | |
|---|---|
| `GET /auth/oidc/{id}/login?redirect=/path` | redirects to the provider |
| `GET /auth/oidc/{id}/callback` | exchanges the code, verifies the ID token and nonce, starts a session |

The PKCE verifier, nonce and state travel in a short-lived `HttpOnly` cookie scoped to `/auth/oidc`,
so sign-in needs no server-side state and works across instances. The session's principal is renewed
with the refresh token when its access token expires; `POST /auth/logout` ends it and continues to the
provider's `end_session_endpoint`. The client authenticates with `client_secret_basic`. Register
`{origin}/auth/oidc/{id}/callback` as a redirect URI and `{origin}/` as a post-logout redirect URI;
behind a proxy, forward `X-Forwarded-Proto` and `X-Forwarded-Host`.

Each provider is listed at `/auth/methods` (`"kind":"redirect"`) and published to OpenAPI as an
`openIdConnect` scheme.

## Providers added at runtime

```java
OidcExtension oidc = new OidcExtension(central);
oidc.register(OidcProvider.of("acme", "https://login.acme.example/", clientId, secret));   // an organization's own IdP
oidc.unregister("acme");
```

Discovery runs inside `register`, which refuses a provider — or any endpoint its discovery names — that
is not https on a public address: registration makes the server fetch URLs someone else chose.
`allowLocalProviders()` lifts that for development. Registered providers serve bearer tokens and
`/auth/oidc/{id}/login` immediately, but are not listed at `/auth/methods` or in OpenAPI: which provider
a given user signs in with is the application's decision — typically an `AuthenticationEntryPoint` that
picks one from the email domain. Their issuer is also a tenant boundary the application must enforce
in its `UserResolver`: a user belongs to the organizations whose issuer vouched for them.

## Principal and roles

`OidcPrincipal` carries the verified claims (`issuer()`, `name()` = `sub`, `email()`, `claim(...)`),
the access token and, for sessions, the refresh token. `hasScope` reads `scope`/`scp`; `hasAudience`
reads `aud`.

Roles are the application's decision. To take them from the token instead:

```java
new SecurityExtension().roles(ClaimRoles.at("realm_access.roles"))   // Keycloak; "groups" for most others
```

## Testing

`FakeOidcProvider` in [`flash-ext-security-test`](../../flash-ext-security-test/docs/README.md) serves
discovery, keys and the code and refresh flows on a local port; `OidcTokens.passwordGrant(...)` gets a
real token from a real provider such as Keycloak in Testcontainers.
