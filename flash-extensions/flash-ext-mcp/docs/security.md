# Security

## Authentication

With `McpSecurity.REQUIRED` (the default) every call must be authenticated by the app's
`flash-ext-security-core` chain: OAuth bearer tokens, API keys, any `AuthenticationMechanism`. The
`SecurityModule` must be installed, otherwise the boot fails and names the server. `McpSecurity.NONE`
makes a public server and requires no security module.

A request with no credential gets `401`. When at least one of the endpoint's schemes has an OAuth
issuer, the challenge is `Bearer resource_metadata="<origin>/.well-known/oauth-protected-resource<rootPath>"`,
so an MCP client can discover the authorization server. Otherwise the challenge lists the schemes.

To restrict the endpoint to some mechanisms, name them in the config:

```java
McpConfig.builder("orders").mechanisms(oidc, apiKeys)
```

Then any other credential, the session cookie included, counts as none on this endpoint, and the
protected resource metadata names only those mechanisms' issuers.

## Protected resource metadata

When an issuer is among the schemes, `GET /.well-known/oauth-protected-resource<rootPath>` returns
RFC 9728 metadata: `resource` (`<origin><rootPath>`), `authorization_servers`, and `scopes_supported`
when configured with `scopesSupported(...)`. Without an issuer it answers `404`.

The issuer `"/"` (the app's own authorization server from
[flash-ext-security-oauth-server](../../flash-ext-security-oauth-server/README.md)) resolves to the app's
origin.

## Audience

By default a token must name the endpoint in its audience: `principal.hasAudience(<origin><rootPath>)`
(RFC 8707), otherwise `403` and a warning log. Credentials that carry no audience (API keys) pass.
`requireTokenAudience(false)` disables the check, for authorization servers that cannot mint a
resource audience. Every token a registered issuer signs is then accepted.

## Origin

The `Origin` header is checked against `allowedOrigins(...)`. A present origin that is not listed is
`403`. A request with no `Origin` (a non-browser client) is allowed. **If no origins are configured the
check is disabled** and a warning is logged at boot. Set it in production, for DNS rebinding
protection.

## Per-tool authorization

`@Authenticated`, `@RolesAllowed` and `@ScopesAllowed` on a tool class are enforced on each
`tools/call`. A denial is a tool error result and the tool is not called. For `@RolesAllowed(on = ...)`
the named values are read from the call's arguments (`ToolArguments.getString`).

```java
@Tool(name = "close_project", args = @ToolArg(name = "project", required = true))
@RolesAllowed(value = "owner", on = "project")
final class CloseProject implements McpTool { ... }
```

`@RolesAllowed` needs a `RoleResolver` configured in `SecurityModule`, otherwise the boot fails. A tool
with a security annotation on a server running with `McpSecurity.NONE` fails the boot.

## Extra middleware

`McpConfig.middleware(...)` adds middleware to the MCP route after the origin guard and
authentication: rate limiting, auditing, tracing.
