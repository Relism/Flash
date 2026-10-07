# Using form login

## The route

`POST /auth/form/login` with a form-encoded body (`username`, `password`), which is what an HTML
form sends and what `fetch` sends with `URLSearchParams`.

| Outcome | Response |
|---|---|
| Missing `username` or `password` | `400` |
| Unknown username or wrong password | `401`, identical for both |
| Success | A session is started, answer is `303` to the `redirect` query parameter (same-origin paths only) or `/` |

The route is also listed in `GET /auth/methods` as login method `form`, kind `form`.

## The store

```java
@FunctionalInterface
public interface PasswordStore {
    Account find(String username);
    record Account(Principal principal, String passwordHash) {}
}
```

`passwordHash` is what the configured `PasswordEncoder` produced. The `Principal` is yours; it is
what the session holds.

## Password hashing

```java
FormLoginModule module = new FormLoginModule(store).passwordEncoder(myEncoder);
```

The default is `PasswordEncoder.pbkdf2()`: PBKDF2-HMAC-SHA256, 600,000 iterations, a random 16-byte
salt, using only the JDK. The encoded form is `pbkdf2$<iterations>$<salt>$<hash>`, with base64
parts. `matches` compares in constant time and returns `false` for anything it did not produce.

To create an account:

```java
String hash = PasswordEncoder.pbkdf2().encode(rawPassword);
```

When no account matches, the module still verifies the password against a hash of a dummy value, so
an unknown username costs the same time as a wrong password.

## Rate limiting

Each attempt costs a password hash, deliberately, and the module does not limit attempts. Put a
limit on the route with `flash-ext-limiter`, for example
`limiter.limit("ip", 5, 1, TimeUnit.MINUTES)` as middleware on a route you register yourself, or
`use` the limiter for the `/auth/form/login` path.
