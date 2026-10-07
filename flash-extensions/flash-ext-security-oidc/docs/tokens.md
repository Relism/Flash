# Bearer tokens

`Oidc` authenticates `Authorization: Bearer <jwt>` requests.

1. A value that is not a JWT with two dots is not this mechanism's: `null` is returned and the next
   mechanism is tried. A JWT that cannot be parsed is rejected.
2. The token is matched to a provider by its `iss` claim. An issuer that is not configured is not
   this mechanism's, so other mechanisms (API keys, an OAuth server) can take it.
3. For a known issuer the signature is checked against the provider's keys (RSA and EC families),
   the `typ` must be `at+jwt` (RFC 9068), `iss` must match, and `sub` and `exp` must be present.
4. Any failure is `401` with `Bearer error="invalid_token"`.

ID tokens carry a different type and are not accepted as access tokens.

The result is an `OidcPrincipal` whose `claims()` are the verified access token's claims.

## Scopes and audience

`hasScope(scope)` reads the `scope` claim (space-delimited string or collection) or `scp`. A token that
has neither grants no scope. `hasAudience(audience)` reads `aud`.

```java
@ScopesAllowed("orders:read")
final class ListOrders extends RequestHandler { ... }
```

## Roles from claims

`ClaimRoles` is a `RoleResolver` that reads roles from the token:

```java
new SecurityModule().roles(ClaimRoles.at("realm_access.roles"))    // Keycloak
new SecurityModule().roles(ClaimRoles.at("groups"))
```

The path is dot-separated through nested claims. The value must be a list. For a principal that is
not an `OidcPrincipal`, the roles are empty. `ClaimRoles.of(principal)` returns the list.

## OpenAPI

Each configured provider is documented as an `openIdConnect` scheme named by its id, with the
discovery URL. Operations that require a caller list the schemes and, for scopes, the required scope
names.
