# Security

Provider-specific setup steps (not generic OAuth2 mechanics) live in separate cookbooks —
[`keycloak.md`](keycloak.md) for Keycloak: enabling Dynamic Client Registration, why the RFC 8707
audience mapper needs to go on the built-in `basic` scope instead of a custom one, and the exact
Allowed Client Scopes configuration `scopes_supported` needs to actually work.

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

## OAuth2 resolution details — zero-config by default

When oidc is available and `security() != NONE`, `McpOidcIntegration` (an isolated,
lazily-loaded bridge — see its javadoc) derives everything an MCP OAuth2 resource server needs
straight from the installed `OidcMiddleware`, with no additional `McpConfig` calls required:

1. The MCP route is wrapped with `flash-ext-oidc`'s own `OidcMiddleware.protect(resourceMetadataPath)`
   — the same Bearer-token/JWKS validation path used everywhere else in Flash5, plus a
   `resource_metadata` challenge parameter (see below). No JWT parsing or JWKS handling is
   reimplemented here.
2. An audience guard always runs after `protect(...)`: it reads the validated claims from
   `ClaimsHolder` and rejects (`403`) any token whose `aud` claim does not include the resource
   identifier — **RFC 8707 Resource Indicators / audience binding**, enforced unconditionally,
   not opt-in. `OidcMiddleware` itself validates `aud` against its own `clientId` for ID
   tokens, but deliberately does not enforce audience on access tokens (it varies by provider)
   — the MCP extension adds that check on top, scoped to its own resource identifier.
3. The resource identifier is the canonical URI of the MCP endpoint, resolved **per request** by
   `OidcMiddleware#selfOrigin` + `rootPath` — the same scheme/host resolution `OidcExtension`
   uses for its own redirect URIs: `X-Forwarded-Host`/`X-Forwarded-Proto` when the request came
   through a reverse proxy, otherwise `{selfScheme()}://{Host header}`. Behind a proxy the
   `Host` alone is the upstream address the proxy dialled, which would publish a resource
   identifier no client can reach. `McpConfig.resourceIdentifier(...)` still overrides it
   outright for a proxy that forwards neither header.
4. The authorization server issuer is read from `OidcMiddleware#issuer()` unless
   `McpConfig.authorizationServerIssuer(...)` overrides it.

## RFC 9728 Protected Resource Metadata

Whenever the endpoint ends up protected, `flash-ext-mcp` publishes a Protected Resource Metadata
document at `/.well-known/oauth-protected-resource{rootPath}` — no explicit `resourceIdentifier`/
`authorizationServerIssuer` configuration required, both are auto-derived as described above:

```json
{ "resource": "https://mcp.example.com/mcp", "authorization_servers": ["https://auth.example.com/realms/myrealm"] }
```

`resource` is computed per request from the incoming request's forwarded/`Host` headers (see
above), so the document is correct without hardcoding the server's own public URL.

### `scopes_supported`

Optional per RFC 9728, omitted from the document entirely unless set via
`McpConfig.scopesSupported("openid", "profile", "email")`:

```json
{ "resource": "...", "authorization_servers": ["..."], "scopes_supported": ["openid", "profile", "email"] }
```

This is pure advertisement — token validation doesn't change based on it — but it matters in
practice: a client that ignores it and requests no scope at all (many do — see `keycloak.md`)
only gets back whatever the authorization server treats as always-included regardless of
request, which for Keycloak is just its built-in `basic` scope. A client that *does* read
`scopes_supported` and echoes it back in its authorization/token requests gets a token with the
claims those scopes actually provide (`profile` → `preferred_username`/`name`, etc.), without
needing every one of those claims hand-mapped onto `basic`. Set it to whatever scopes your
`McpTool`s actually read off `ClaimsHolder`/`OidcUser` — there's no way to auto-derive this list,
it depends entirely on what your tools do with the claims.

## `WWW-Authenticate: resource_metadata` (RFC 9728 §5.1)

The MCP Authorization spec **requires** a `401` to carry `resource_metadata` in
`WWW-Authenticate`, pointing at the Protected Resource Metadata document above — this is how a
spec-compliant client discovers the authorization server without out-of-band configuration.
`OidcMiddleware.protect(String resourceMetadataPath)` (an overload added specifically for this)
builds that challenge automatically:

```
WWW-Authenticate: Bearer realm="...", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp"
```

The plain `OidcMiddleware.protect()` (no argument), used by every other Flash5 app, is
unaffected — this parameter is additive and MCP-specific.

## Per-tool `@RolesAllowed`/`@ScopesAllowed`

`McpTool` subclasses can carry `flash-ext-oidc`'s `@RolesAllowed`/`@ScopesAllowed`:

```java
@Tool(name = "delete_route", description = "Delete a route")
@RolesAllowed("admin")
public class DeleteRouteTool extends McpTool {
    @Override public ToolResponse call(ToolArguments args) { ... }
}
```

This does **not** reuse `flash-ext-oidc`'s per-route middleware mechanism (`ctx.addAnnotationProcessor`,
the thing that makes these annotations work on a `RequestHandler`) — it can't: every tool shares
one HTTP route (`POST {rootPath}`), already wrapped by whatever `McpSecurity` resolved above, so
there is no per-tool route to attach a different middleware chain to. Instead,
`McpOidcIntegration.compileToolPolicy` reads the annotations once at boot (`McpRegistry.scan`)
and compiles them into a closure (`McpAuthPolicy`) that `McpDispatcher` runs *after* the
route-wide auth has already succeeded and *before* invoking the specific tool named in the
`tools/call` request — narrowing what's already-authenticated, not replacing it. A denial is a
normal `isError: true` tool result (see `ToolResponse.error`), not an HTTP-level rejection — the
model sees why, the same as any other tool failure.

Roles are read via `OidcUser#hasRole` against `McpConfig.rolesClaimPath(...)` (default
`"realm_access.roles"`, matching `OidcConfig`'s own default — set this explicitly if the two
diverge; there's no way to read `OidcConfig`'s actual configured value from here). Scopes use
`OidcUser#hasScope`'s built-in default claim paths (`scope`/`scp`), no extra config needed.
`@ScopesAllowed(match = ScopesAllowed.Match.ANY)` and multi-role `@RolesAllowed({"admin",
"editor"})` (OR semantics) both work exactly as they do on a `RequestHandler`.

**`@Authenticated` alone has no effect and fails boot.** Once oidc is active for a server, every
tool call is already authenticated — there's no per-tool public/authenticated split the way
there is for HTTP routes, so a bare `@Authenticated` on a tool can't mean anything and would
silently do nothing if allowed to compile. Boot fails instead, with a message pointing at
`@RolesAllowed`/`@ScopesAllowed` as the actual narrowing mechanism.

**Annotating a tool without active OAuth2 also fails boot**, not silently at request time: if
`@RolesAllowed`/`@ScopesAllowed`/`@Authenticated` shows up on a tool while `McpSecurity` resolved
to unprotected (`NONE`, or `AUTO` with no oidc installed), that's very likely a forgotten
`OidcExtension` install or a `McpSecurity.NONE` left over from local dev — `IllegalStateException`
at `app.start()`.

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
