# Interop

## flash-ext-auth-core

A hard dependency, and the reason this extension is as small as it is. The division:

| Here | `flash-ext-auth-core` |
|---|---|
| Discovery, JWKS, PKCE, token endpoint | `@Authenticated`, `@RolesAllowed`, `@ScopesAllowed` |
| `/login`, `/callback`, `/logout` | `ClaimsHolder`, `Claims` |
| Bearer and cookie resolution, refresh | Role and scope matching |
| RFC 6750 `WWW-Authenticate` challenges | `Session`, `SessionStore` |

`OidcExtension` builds an `OidcCredentialSource`, hands it to `AuthMiddleware.install(...)`, and
that publishes the middleware and registers the annotation processor. Everything a handler
annotation does is core's code running against claims this extension produced.

Consequence worth knowing: `@RolesAllowed` is not OIDC-specific and never was. An app that swaps
this extension for another credential source keeps every annotation it had.

## flash-ext-openapi

Optional, and resolved lazily so this extension runs standalone when openapi is not on the
classpath. When it is, an `OpenApiContributor` is registered that emits an `oauth2` security scheme
with the `authorizationCode` flow, filled in from the discovery document:

```json
"securitySchemes": {
  "myrealm": {
    "type": "oauth2",
    "flows": { "authorizationCode": { "authorizationUrl": "…", "tokenUrl": "…", "scopes": {…} } }
  }
}
```

Per-operation security comes from the same annotations the middleware reads, so the spec and the
enforcement cannot drift: both call `AuthPolicy.compileFromAnnotations`.

The scheme name is `schemeName`, derived from the last path segment of the issuer (a Keycloak realm
name, usually) unless set explicitly.

## flash-ext-mcp

`McpSecurity` asks whether **this** extension is installed — `ctx.find(OidcCredentialSource.class)`
— and not merely whether something authenticates:

| Policy | this extension installed | absent |
|---|---|---|
| `REQUIRED` | protected | **boot fails** |
| `AUTO` | protected | unprotected, warning logged |
| `NONE` | never protected | unprotected |

That distinction is deliberate. `REQUIRED` means "a real OAuth2 authorization server is protecting
this endpoint", because everything it turns on — RFC 9728 Protected Resource Metadata, RFC 8707
audience binding, `WWW-Authenticate` challenges carrying `resource_metadata` — is meaningless
without an issuer. An app that authenticates some other way must not satisfy it by accident.

When it is installed, `McpOidcIntegration` derives the whole resource-server configuration from the
source with no extra `McpConfig` calls:

- the MCP route is wrapped with `authMw.withSource(source.withResourceMetadata(path)).protect()` —
  the same validation every other route uses, plus the `resource_metadata` challenge parameter;
- an audience guard runs after it and rejects any token whose `aud` does not include this
  endpoint's resource identifier;
- the resource identifier is resolved per request from `X-Forwarded-Host`/`-Proto`, or the `Host`
  header and `selfScheme`.

An app that does **not** use OAuth2 can still guard `/mcp`: set `McpSecurity.NONE` and pass its own
guard to `McpConfig.middleware(...)`.

## Migrating from flash-ext-oidc

The module was renamed and its generic half moved. Mechanically:

| Was | Now |
|---|---|
| `flash-ext-oidc` (artifact) | `flash-ext-auth-oidc` |
| `dev.relism.flash.ext.oidc.Authenticated` (and `RolesAllowed`, `ScopesAllowed`) | `dev.relism.flash.ext.auth.…` |
| `OidcMiddleware` | `AuthMiddleware` (`dev.relism.flash.ext.auth`) |
| `ctx.find(OidcMiddleware.class)` | `ctx.find(AuthMiddleware.class)` |
| `OidcUser` | `Claims` |
| `ClaimsHolder.user()` | `ClaimsHolder.current()` |
| `ClaimsHolder.get()` | `ClaimsHolder.map()` |
| `OidcSession`, `OidcSessionStore`, `InMemoryOidcSessionStore` | `Session`, `SessionStore`, `InMemorySessionStore` |
| `session.isAccessTokenExpired()` | `session.isExpired()` |
| `session.idToken()` | `session.attributeAsString(OidcCredentialSource.ID_TOKEN)` |

`OidcConfig`, `OidcExtension` and every setting on them are unchanged.
