# Security

The MCP endpoint is secured by [`flash-ext-security-core`](../../flash-ext-security-core/docs/README.md):
whatever mechanisms the application registers — OAuth2 bearer tokens, API keys, custom ones —
authenticate `/mcp` exactly as they authenticate every other route.

| `McpConfig.security(...)` | |
|---|---|
| `REQUIRED` (default) | every call must be authenticated; boot fails without a `SecurityExtension` |
| `NONE` | a public endpoint; a tool carrying security annotations fails the boot |

## OAuth2 protected resource

When a registered mechanism publishes an OAuth2 issuer — `flash-ext-security-oidc` does — the endpoint
behaves as the MCP authorization spec requires, with nothing to configure:

- `GET /.well-known/oauth-protected-resource/mcp` serves RFC 9728 metadata: the `resource` (the
  application's `SecurityExtension.origin(...)` plus the path), every issuer as `authorization_servers`,
  and `scopes_supported` when `McpConfig.scopesSupported(...)` is set;
- an anonymous call gets `401` with `WWW-Authenticate: Bearer resource_metadata="…"`;
- a token whose `aud` does not include the resource is `403` (RFC 8707) and logged at `WARN`. Credentials
  that are not audience-bound, such as API keys, are unaffected.

For Keycloak, the audience comes from an *Audience* protocol mapper whose included custom audience is
the resource URL, attached to a client scope every MCP client receives (the built-in `basic` scope is the
one that needs no client cooperation). Clients that register dynamically need Keycloak's anonymous
client registration policies relaxed for the trusted hosts.

`McpConfig.requireTokenAudience(false)` drops that last check for an authorization server that cannot
mint a resource audience at all — Keycloak ignores RFC 8707's `resource` parameter, so a deployment that
cannot add the mapper has no other way in. Every token a registered issuer signs is then accepted on the
endpoint, and the boot logs say so.

## Which credentials

By default every mechanism in the chain authenticates `/mcp`, and the session cookie too.
`McpConfig.mechanisms(...)` narrows that to the ones named: nothing else is a credential on the endpoint,
and only their issuers are published — so a client is sent to exactly the authorization server the
endpoint trusts.

```java
McpConfig.builder("app").toolsPackage("com.example.tools").mechanisms(authorizationServer).build();
```

## Tool policies

The core annotations work on tools as on handlers, checked per `tools/call` against the caller the route
authenticated:

```java
@Tool(name = "approve", description = "Approves a pending proposal")
@RolesAllowed(value = "REVIEWER", on = {"project", "locale"})   // read from the tool's arguments
public class ApproveTool extends McpTool { … }
```

A denial is a tool result with `isError: true` — the call reached the server, the tool did not run.

`McpConfig.middleware(...)` runs after authentication, for rate limiting, auditing or tracing.
