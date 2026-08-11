# Security

## `McpSecurity`

`McpConfig.security(...)` controls how the MCP endpoint reacts to `flash-ext-oidc` being
installed (`ctx.find(OidcMiddleware.class)`), resolved once at boot in `McpExtension.routes()`:

| Policy | `flash-ext-oidc` installed | `flash-ext-oidc` absent |
|---|---|---|
| `REQUIRED` | protected | **boot fails** (`IllegalStateException`) |
| `AUTO` (default) | protected | runs unprotected, logs a warning |
| `NONE` | never protected, even if oidc is installed elsewhere in the app | runs unprotected |

Use `REQUIRED` for anything you intend to run in production reachable over the network — it
turns "someone forgot to wire up OAuth2" into a startup crash instead of a silently open
endpoint. `AUTO` is meant for local development, where spinning up a real identity provider is
friction you don't want yet.

## Why `flash-ext-oidc` is an *optional* Maven dependency, concretely

Maven's `<optional>true</optional>` only affects **transitive** propagation: consumers of
`flash-ext-mcp` don't get `flash-ext-oidc` pulled in automatically unless they add it themselves.
Within `flash-ext-mcp` itself, `flash-ext-oidc`'s classes are on the compile/test classpath as
normal — this extension can (and does) reference `OidcMiddleware`/`ClaimsHolder` directly in
source.

That reference is isolated in its own class, `McpOidcIntegration`, invoked only from inside a
`catch (NoClassDefFoundError)` block. A bare class-literal like `OidcMiddleware.class` (which
`ctx.find(OidcMiddleware.class)` needs) forces the JVM to resolve that type the moment it's
evaluated — if `flash-ext-oidc` is not on the *runtime* classpath at all (a genuinely
MCP-only install, no OAuth2 anywhere in the app), the first such reference throws
`NoClassDefFoundError`. Keeping that reference inside a separate, lazily-loaded class means
`McpExtension` itself loads and works fine standalone; only the attempt to actually use OIDC
fails, and only when there's something to fail. This mirrors `OidcExtension`'s own lazy bridge to
`flash-ext-openapi` — same technique, same reason.

## OAuth2 resolution details

When oidc is available and `security() != NONE`:

1. The MCP route is wrapped with `flash-ext-oidc`'s own `OidcMiddleware.protect()` — the same
   Bearer-token/JWKS validation path used everywhere else in Flash5. No JWT parsing or JWKS
   handling is reimplemented here.
2. If `McpConfig.resourceIdentifier(...)` is set, an additional audience guard runs after
   `protect()`: it reads the validated claims from `ClaimsHolder` and rejects (`403`) any token
   whose `aud` claim does not include the configured resource identifier — **RFC 8707 Resource
   Indicators / audience binding**. This is genuinely new behavior, not something
   `flash-ext-oidc` does on its own: `OidcMiddleware` validates `aud` against its own
   `clientId` for ID tokens, but deliberately does not enforce audience on access tokens (it
   varies by provider) — the MCP extension adds that check on top, scoped to its own resource
   identifier.
3. If `resourceIdentifier(...)` is left unset, only standard bearer validation runs — no
   audience binding. Fine for a first integration; RFC 8707 becomes meaningful once you have
   more than one resource server sharing the same authorization server.

## RFC 9728 Protected Resource Metadata

If both `resourceIdentifier(...)` and `authorizationServerIssuer(...)` are set (and the endpoint
ends up protected), `flash-ext-mcp` publishes a Protected Resource Metadata document at
`/.well-known/oauth-protected-resource{rootPath}`:

```json
{ "resource": "https://mcp.example.com/mcp", "authorization_servers": ["https://auth.example.com/realms/myrealm"] }
```

This lets a spec-compliant MCP client discover which authorization server to use without
out-of-band configuration. `authorizationServerIssuer` has to be supplied explicitly because
`flash-ext-oidc` does not expose its resolved issuer/discovery metadata through `FlashContext` —
only `OidcMiddleware` and `JwtValidator` are registered there. Passing it separately avoids
reaching into `flash-ext-oidc` internals for a value the app owner already has at hand (it's the
same issuer they configured `OidcExtension` with).

Without an issuer configured, bearer validation still works exactly the same — the client just
needs the authorization server configured out-of-band instead of discovering it automatically.

## The `HttpException` safety net

`flash-ext-oidc`'s middleware throws `HttpException.unauthorized()`/`forbidden()` on auth
failure. Flash5's core does **not** special-case `HttpException` in the default exception
handler — the out-of-the-box `AbstractRouter` default always returns a generic `500`, regardless
of the thrown exception's embedded status code; only an app that explicitly calls
`FlashApp#onException(...)` (or installs something that does) gets `HttpException.status()`
honored.

To keep the MCP endpoint correct regardless of what the rest of the app configures,
`McpTransportGuards.httpExceptionGuard()` wraps the whole route and translates `HttpException`
into the right HTTP status itself, rather than letting it fall through to the app's (possibly
unconfigured) global handler. This is scoped entirely to the MCP route — it does not touch or
override the app's `onException` for any other route.
