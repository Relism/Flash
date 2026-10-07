# SecurityModule

```java
app.install(new SecurityModule()
        .users(principal -> userRepository.byPrincipal(principal))
        .roles((identity, role, on) -> memberships.has(identity.principal().name(), role, on.get("project")))
        .origin("https://app.example")
        .loginPage("/login")
        .sessionTimeout(Duration.ofHours(8)));
```

| Method | Default | Meaning |
|---|---|---|
| `users(UserResolver<?>)` | the principal itself | Maps a verified `Principal` to your user object, returned by `SecurityIdentity.user(Class)` |
| `roles(RoleResolver)` | none | Decides `@RolesAllowed`. A handler that declares roles without a resolver fails the boot |
| `entryPoint(AuthenticationEntryPoint)` | see below | Answers a request that needs a caller and carries no credential |
| `sessions(SessionStore)` | `InMemorySessionStore` | Where sessions live. See [sessions.md](sessions.md) |
| `sessionTimeout(Duration)` | 12 hours | Lifetime of a session started with `signIn` |
| `loginPage(String)` | none | Where a browser goes to sign in |
| `origin(String)` | none | Where the app is served, such as `https://app.example`. A trailing `/` is removed |

**Set `origin` in every deployment.** Without it, session cookies, sign-in callbacks and token
audiences are built from the request's `Host` header, which the client chooses.

## Default entry point

For a request that needs a caller and has no credential:

- A browser (the `Accept` header contains `text/html`) is redirected to the login page, with
  `?redirect=<original path and query>`. The login page is `loginPage` if set; otherwise the URL of
  the only login method when exactly one exists and it is a redirect; otherwise `/login`. This
  redirect happens only when a login page or at least one login method exists.
- Anything else gets `401` with a `WWW-Authenticate` header listing the challenge of every
  mechanism's schemes.

## Routes

| Route | Behavior |
|---|---|
| `POST /auth/logout` | Ends the session and answers `303` to the principal's `logoutUrl()`, or `/` |
| `GET /auth/methods` | JSON array of login methods: `[{"id","name","url","kind"}]` with `kind` `redirect` or `form` |

Login methods come from the `LoginMethod` services other modules add. The JSON is encoded once at
start.

## The Security service

`SecurityModule` registers `Security`, which handlers and modules take as a constructor parameter.
For code that runs before the app starts, `module.security()` returns the same object.

| Method | Purpose |
|---|---|
| `authenticate(Request)` | The `SecurityIdentity`, or `null`. Throws `AuthenticationFailedException` for a rejected credential |
| `signIn(req, res, principal)`, `signIn(..., expiresAt)` | Start a session and set its cookie |
| `signOut(req, res)` | End the session; returns where the browser should go |
| `origin(req)` | The configured origin, or the request's |
| `schemes()` | The security schemes of all mechanisms |
| `policy(Class)`, `enforce(policy[, entryPoint[, mechanisms]])` | Build the guard middleware yourself. See [authorization.md](authorization.md#guarding-a-route-by-hand) |

Mechanisms, login methods and session refreshers are read once when the app starts: add them to the
app as services and `Security` finds them.
