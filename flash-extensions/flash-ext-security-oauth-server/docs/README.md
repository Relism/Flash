# flash-ext-security-oauth-server

An OAuth 2.1 authorization server for [`flash-ext-security-core`](../../flash-ext-security-core/docs/README.md):
the application's own users authorize clients — an MCP client, a CLI, another service — to call the
application's own resources. It is the server side of OAuth; signing users in *through* someone else's
provider is [`flash-ext-security-oidc`](../../flash-ext-security-oidc/docs/README.md).

```java
SecurityModule security = new SecurityModule().origin("https://app.example").loginPage("/login");
OAuthServer oauth = new OAuthServer("/mcp")   // the resources tokens are for
        .store(new JdbcOAuthStore(db))                          // default: in memory
        .signingKeys(System.getenv("OAUTH_SIGNING_KEYS"));       // default: generated at boot

app.install(security, new CaffeineCacheModule(), oauth,
        new McpModule(McpConfig.builder("app").toolsPackage("com.example.tools").mechanisms(oauth).build()));
```

The issuer is `SecurityModule.origin(...)`. The server is also an `AuthenticationMechanism`: a bearer
token it issued authenticates as an `OAuthPrincipal` (`name()` is `sub`, `clientId()`, `claim(...)`,
`hasScope`, `hasAudience`). Any other bearer is left to other mechanisms. A token is good only under the
resource it was issued for: one for `/mcp` is `401 invalid_token` on `/api/...`, whatever the route asks.

## What it implements

| | |
|---|---|
| OAuth 2.1 | authorization code with PKCE `S256` only; exact redirect URIs; no implicit, no password grant |
| RFC 8252 | a loopback redirect (`http://127.0.0.1`, `[::1]`, `localhost`) may come back on any port |
| RFC 8414 | `GET /.well-known/oauth-authorization-server`, CORS-enabled like every client-facing endpoint |
| Client ID metadata documents | a `client_id` that is an https URL is fetched, under `PublicUrl`'s rules, and trusted only if it names itself |
| RFC 7591 | `POST /oauth/register`; `registration(false)` closes it |
| RFC 8707 | `resource` on authorize and token requests, one of the constructor's paths on the origin; the first is the default |
| RFC 9068 | access tokens are ES256 JWTs, `typ` `at+jwt`, with `iss`, `sub`, `aud`, `client_id`, `scope`, `iat`, `exp`, `jti`; `GET /oauth/jwks` |
| Refresh tokens | opaque, stored hashed, rotated on every use; reusing one — or replaying a code — revokes everything issued from that authorization |
| RFC 7009 | `POST /oauth/revoke` takes a refresh token's whole authorization with it |
| RFC 9207 | `iss` in every authorization response |
| `client_credentials` | only for a client the application registers itself, with `register(metadata)` |

Clients authenticate at the token and revocation endpoints with `none` (public, PKCE), `client_secret_basic`
or `client_secret_post` — whichever they registered. Secrets, codes and refresh tokens are 256 random bits,
stored as SHA-256.

Not implemented: DPoP, mTLS, PAR, JAR, `private_key_jwt`, device authorization, token exchange,
introspection (the access token is self-contained: verify it with the JWKS), RFC 7592 client management,
and OpenID Connect — there are no ID tokens and no userinfo.

## Signing in and consent

`GET /oauth/authorize` runs behind the security chain: a browser without a session goes to the
application's sign-in (set `loginPage`, so a page that knows every way in is shown rather than the only
listed provider), and comes back when it is signed in.

The first time a user meets a client, and whenever it asks for more scope than they allowed, the browser
is sent to `consentPage(...)` (default `/consent`) with the authorization request as its query string.
That page reads what to show from `GET /oauth/authorize/request?<same query>` —
`client_name`, `client_uri`, `logo_uri`, `redirect_uri`, `scope`, `resource` — and submits a form
`POST /oauth/authorize` with the same parameters and `consent=allow` or `consent=deny`. The form is
refused if its `Origin` is not the application's; the session cookie is `SameSite=Lax`, so another
site's form does not carry it.

An unknown client or a redirect URI it did not register is answered with a 400, never a redirect; any
other refusal goes back to the redirect URI as `error`, with `state` and `iss`.

## Subjects

`subjects(identity -> new OAuthSubject(id, claims))` decides what a token says about who authorized it:
`id` becomes `sub`, `claims` go into every token issued from that authorization, refreshes included.
Default: the principal's name, no claims. Put there whatever the application must know on the other side
— which provider signed the user in, for instance, when that is a tenant boundary.

The same function decides who may authorize at all: throwing refuses the caller. `/oauth/authorize` runs
behind the whole chain, so refuse any credential narrower than the user behind it — an API key scoped to
one project must not come back as a token carrying everything its user may do.

## Caches

The server needs a `CacheManager` — install [`flash-ext-cache-caffeine`](../../flash-ext-cache-caffeine/docs/README.md).
It keeps client metadata documents there, bounded and for ten minutes, failures included: the
`client_id` is a URL whoever calls the server chose, so neither the cache nor the fetching it saves
may grow with what they send. The RFC 8414 document is cached per issuer.

## Storage and keys

`OAuthStore` holds clients (their RFC 7591 metadata, verbatim), grants — codes and refresh tokens, one
`OAuthGrant` record, keyed by hash and grouped in a family per authorization — and consents.
`use(hash)` must be atomic: it is what makes a code single-use. `InMemoryOAuthStore` loses everything
on restart.

`signingKeys(jwkSet)` takes a JWK set whose first key is a private P-256 key; the others only verify, so a
key rotates by putting its successor first. `OAuthServer.generateSigningKeys()` makes one.

## Testing

`OAuthTestClient` in [`flash-ext-security-test`](../../flash-ext-security-test/docs/README.md) registers,
authorizes as any principal, consents and exchanges, discovering every endpoint from the metadata.
