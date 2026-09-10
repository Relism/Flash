# Keycloak cookbook

`security.md` covers the OAuth2 mechanics `McpOidcIntegration` implements against any
`flash-ext-auth-oidc`-compatible provider. This is the Keycloak-specific setup: the exact Admin
Console configuration for a working MCP OAuth2 flow with open Dynamic Client Registration
(DCR) — no pre-registered clients, any MCP client self-registers on first connect.

## 1. Allow Dynamic Client Registration

MCP clients (Claude Desktop, Claude.ai, MCP Inspector, others) don't share one static OAuth
client — each has its own `redirect_uri` and none know your realm in advance. They self-register
on first connect via `POST {issuer}/clients-registrations/openid-connect` (the
`registration_endpoint` from the AS metadata document, reached via the RFC 9728 Protected
Resource Metadata document `McpExtension` publishes).

**Clients → Client registration**: remove the **Trusted Hosts** policy — it rejects anonymous
registration from hosts not on an explicit allowlist (`403` / `"Host not trusted"`), which
doesn't scale to arbitrary future agents. This does not weaken end-user authentication — DCR
only grants an app a `client_id`; every user still authenticates against Keycloak's real login
screen regardless of which client asked. Lighter hygiene policies (**Max Clients Limit**,
**Consent Required**) can stay, they don't interfere.

## 2. RFC 8707 audience: mapper on `basic`, not a custom scope

`McpOidcIntegration` rejects (403) any token whose `aud` doesn't include the MCP endpoint's
canonical URL. Keycloak doesn't add this by default. The obvious fix — a custom client scope
with an Audience mapper, marked Default, added to Allowed Client Scopes — **does not work**:
clients created via the `openid-connect` DCR endpoint only ever get scopes they explicitly
request, and most MCP clients (including MCP Inspector) don't request anything beyond what a
server tells them to via `scopes_supported` (step 3). Default-scope auto-attachment, which is
how a normal manually-created client would pick up a custom Default scope, doesn't apply to
DCR-created clients at all.

`basic` is the one built-in scope Keycloak attaches to every client unconditionally, regardless
of what it registered with. Put the audience mapper there:

1. **Client scopes → `basic`** → **Mappers** → **Add mapper** → **By configuration** →
   **Audience**.
2. **Included Custom Audience** = the exact value your server expects — check
   `GET {parent-of-rootPath}/.well-known/oauth-protected-resource{rootPath}` on the running
   server for the `resource` field it publishes (auto-derived from the request's
   forwarded/`Host` headers — see `security.md`). Leave **Included Client Audience** empty (that
   targets another Keycloak client, not a resource URL).
3. **Add to access token** = ON.
4. **Save.**

This is unconditional and works regardless of client cooperation — keep it even after step 3
below gets other claims flowing normally, since audience binding is a hard spec requirement
that shouldn't depend on a client bothering to request the right scope.

## 3. Other claims (username, email...): `scopes_supported` + Allowed Client Scopes

`OidcUser.username()`/`.email()`/`.name()` read `preferred_username`/`email`/`name` — normally
from the `profile`/`email` client scopes, which DCR clients don't get either, same root cause.
Unlike audience, this **is** fixable the "normal" way, because it doesn't need to survive a
completely uncooperative client:

`McpConfig.scopesSupported("openid", "profile", "email")` publishes those scopes in the PRM
document. MCP clients that read it (confirmed for MCP Inspector) echo them back in their DCR
registration request — `"scope": "openid profile email offline_access"` (`offline_access` is
Inspector's own addition, for refresh tokens). For that request to actually succeed, **Allowed
Client Scopes** needs, exactly:

- **`openid` listed explicitly.** The one genuinely non-obvious step: `openid` is not covered by
  **Allow Default Scopes** (On by default) the way other realm-Default scopes are, even though
  every OIDC request includes it. Until it's listed here, registration fails with a generic
  `403 insufficient_scope` / `"Not permitted to use specified clientScope"` regardless of
  whether everything else is configured correctly.
- **`offline_access` listed explicitly** — it's Optional, not Default, so `ALLOW_DEFAULT_SCOPES`
  doesn't cover it either.
- **`profile`/`email` — do not list them here.** Mark them **Default** on the **Client scopes**
  page (Assigned Type column) instead, and leave **Allow Default Scopes** = On. Adding an
  already-Default scope to this list explicitly gets rejected on save
  (`"Client scopes not allowed: [...]"`) — the list is for *additional* Optional scopes only.

With that, a real client's token comes back with `preferred_username`/`email` populated
normally.

### Fallback for anything else

For a claim not covered by `openid profile email` (a custom attribute, a role) — or for a client
that ignores `scopes_supported` entirely — add a **User Property** mapper to `basic` too
(Property `username` → Token Claim Name `preferred_username`, or whatever's needed), same as the
audience mapper in step 2. Unconditional, works regardless of client cooperation, costs one
mapper per claim, once, at the realm level — not per tool.

## Verifying without a full OAuth round-trip

**Clients → (any client) → Client scopes → Evaluate**: pick a user, run it — Default scopes
(including `basic`) apply automatically and won't appear in the "Select scope parameters"
picker, which only lists Optional ones — and check the **Generated Access Token** preview.
Confirms mappers work without a browser + real MCP client round-trip each time.

## If a real client still gets rejected

`McpOidcIntegration.audienceGuard` logs the actual mismatch at `WARN`:

```
[flash-ext-mcp] Rejecting token (RFC 8707): aud=<token's actual aud> does not include expected
resource identifier "<what this server expects>" — ...
```

`aud=null` → the `basic` mapper produced nothing (most common cause: **Included Custom
Audience** left blank — the mapper saves fine and silently does nothing without it). A non-null
`aud` that still doesn't match → compare byte-for-byte — the expected side is derived from the
request's own forwarded/`Host` headers, so scheme/host/trailing-slash mismatches show up here
directly, as does a proxy hop that drops `X-Forwarded-Host`.
