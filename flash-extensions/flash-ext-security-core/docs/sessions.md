# Sessions

A signed-in principal is kept server side under a random id. The browser holds only a cookie.

## Starting and ending

```java
security.signIn(req, res, principal);                  // lasts sessionTimeout
security.signIn(req, res, principal, expiresAt);       // explicit expiry
String next = security.signOut(req, res);              // returns where to send the browser
```

`signIn` stores a `Session(id, principal, expiresAt)` and sets:

```
Set-Cookie: flash_session=<id>; Path=/; HttpOnly; SameSite=Lax[; Secure]
```

The id is 24 random bytes in base64url. `Secure` is added when the origin starts with `https`.
`signOut` deletes the session, expires the cookie and returns where the browser goes next (see
[Logout at the identity provider](#logout-at-the-identity-provider)).

## Expiry and refresh

A request with a session whose `expiresAt` has passed asks the `SessionRefresher` registered for the
principal's class. A refresher returns a renewed `Session` (same id) or `null`. With `null`, or no
refresher, the session is deleted and the caller is anonymous. `flash-ext-security-oidc` provides a
refresher that uses the provider's refresh token.

```java
public interface SessionRefresher {
    Class<? extends Principal> type();
    Session refresh(Session expired);
}
```

Register with `app.add(SessionRefresher.class, refresher)`.

## Logout at the identity provider

`signOut` returns the principal's `logoutUrl()`, or `/`. When the way out must be built as the user
leaves, such as a logout request signed and dated at that moment, a module registers a `SessionLogout`
for its principal type, and `signOut` asks it instead:

```java
public interface SessionLogout {
    Class<? extends Principal> type();
    String logoutUrl(Session ended, Request req);    // null for /
}
```

Register with `app.add(SessionLogout.class, logout)`. `flash-ext-security-saml` provides one. If it
throws, the failure is logged at WARN and the browser goes to `/`: the local session has ended
regardless.

`endSession(req, res)` ends the session and expires the cookie without asking anyone where to go next,
and returns the ended `Session` or `null`.

## Stores

```java
public interface SessionStore {
    void save(Session session);
    Session find(String id);
    void delete(String id);
}
```

`find` may return an expired session, so a refresher can renew it. `InMemorySessionStore` is the
default: sessions are lost on restart and not shared between instances. It sweeps expired sessions
once every 1,024 saves. For several instances or restarts, implement `SessionStore` on shared
storage and pass it to `SecurityModule.sessions`.

The principal stored must be what your store can persist. `OidcPrincipal` holds tokens and claims.
