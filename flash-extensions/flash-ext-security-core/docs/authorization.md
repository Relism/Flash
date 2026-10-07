# Authorization

Annotate the handler class. A route-hook turns the annotations into middleware when the app boots,
and the middleware is added to the route with the key `Security.POLICY`
(`flash.security.policy`), so other middleware can order itself before or after it.

| Annotation | Requires |
|---|---|
| `@PermitAll` | Nobody. A caller who authenticates is still identified. One whose credential is rejected is treated as anonymous |
| `@Authenticated` | Any authenticated caller |
| `@RolesAllowed("a", "b")` | At least one of the roles. Implies authenticated |
| `@ScopesAllowed("x", "y")` | Every one of the scopes. Implies authenticated |

`@RolesAllowed` and `@ScopesAllowed` can be combined. `@PermitAll` with any other annotation fails
the boot, as does an annotation with no values. Annotations are read from the handler class itself,
not inherited.

A handler with no security annotation is not guarded by this module.

## Outcomes

| Situation | Result |
|---|---|
| No credential, policy requires a caller | The entry point: redirect to login or `401` |
| Credential rejected, policy requires a caller | `401` with the mechanism's challenge |
| Missing scope | `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="..."` |
| Missing role | `403` |

## Roles

Roles are decided by your `RoleResolver`:

```java
@FunctionalInterface
public interface RoleResolver {
    boolean hasRole(SecurityIdentity identity, String role, Target on);
}
```

`@RolesAllowed(value = "editor", on = "project")` names a resource. `on` lists parameter names, read
from the path parameter first and then the query parameter, and handed to the resolver as `Target`:
`on.get("project")`. Without `on`, `Target.NONE` is passed and `get` returns `null`.

With OIDC, `ClaimRoles.at("realm_access.roles")` reads roles from a token claim. See
[flash-ext-security-oidc](../../flash-ext-security-oidc/docs/tokens.md#roles-from-claims).

## Scopes

`Principal.hasScope(scope)` decides. The interface's default is `true`, which means a credential
that carries no scopes (an API key, a form session) grants every scope. OIDC and OAuth principals
read scopes from the token.

## SecurityIdentity

Inside a guarded handler, `SecurityIdentity.current()` returns the caller, or `null` when the caller
is anonymous. It is set while the route's middleware chain runs, so it is available in `handle` and
in other middleware that runs after the policy. It is not set inside a `Response.streaming`
callback, which runs after the chain.

| Method | Returns |
|---|---|
| `principal()` | The `Principal` the mechanism produced |
| `principal(Class<P>)` | The principal as that type, or `null` if another mechanism authenticated the caller |
| `user(Class<U>)` | The application user from `UserResolver`, resolved once per request |
| `hasScope(scope)` | `principal.hasScope` |
| `hasRole(role)`, `hasRole(role, Target)` | Asks the `RoleResolver`. Throws `IllegalStateException` if none is configured |
| `request()` | The request, for resolvers that read something else (a tenant header) |

## Guarding a route by hand

Annotations cover handler classes. For a lambda or a custom rule build the middleware:

```java
Security security = securityModule.security();
SecurityPolicy policy = SecurityPolicy.AUTHENTICATED;
app.get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(policy));
```

`enforce(policy, entryPoint)` answers an anonymous caller with your entry point on that route.
`enforce(policy, entryPoint, mechanisms)` restricts the route to those mechanisms: every other
credential, the session cookie included, counts as no credential. Use it for a bearer-only API that
a browser cookie must not reach.

## OpenAPI

With `flash-ext-openapi`, each mechanism's scheme appears under `components.securitySchemes`. Every
operation that requires a caller lists the schemes as alternatives, and documents `401` and, when it
requires roles or scopes, `403` with the required roles or scopes in the description.
