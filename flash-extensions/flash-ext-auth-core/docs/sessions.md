# Sessions

A `Session` is what a credential source keeps server-side between requests, looked up by a cookie.
Core owns the container; what goes in it is the source's business.

```java
public final class Session {
    String              id();
    Map<String, Object> claims();
    Instant             expiresAt();
    Map<String, Object> attributes();
    boolean             isExpired();
    Object              attribute(String key);
    String              attributeAsString(String key);
}
```

## Why it expires early

`isExpired()` returns true **30 seconds before** `expiresAt`. Without that window a session can
pass the check at the top of a request and be dead by the time the handler uses it — a class of
failure that reproduces once a day and never in a test. Renewal is therefore always slightly
premature, on purpose.

## Attributes

`attributes()` is opaque to this module. `flash-ext-auth-oidc` keeps its access, id and refresh
tokens there under its own keys, which is what lets renewal stay entirely inside that extension
while the session itself carries no OAuth2 vocabulary.

Store what your source needs to renew or revoke, and nothing a handler should be reading — handlers
read `claims()`.

## The store

```java
public interface SessionStore {
    void save(Session session);
    Optional<Session> find(String sessionId);
    void delete(String sessionId);
}
```

`InMemorySessionStore` is the default: a `ConcurrentHashMap`, fine for a single instance, and it
loses every session on restart. Supply your own for Redis or JDBC when sessions have to survive a
deploy or be shared across nodes.

Sessions are immutable. Renewing one builds a new instance with the same `id()` and `save`s it
over the old — there is no mutate-in-place path, so a store can cache or serialise freely.
