# Using Basic authentication

## Setup

`BasicAuth` is a `Module` and an `AuthenticationMechanism`. It takes a `PasswordStore`, the store
form sign-in uses, so one set of accounts serves both.

```java
app.install(new SecurityModule(),
            new BasicAuth(accounts::byUsername).realm("files"));
```

| Method | Default | Effect |
|---|---|---|
| `passwordEncoder(PasswordEncoder)` | `PasswordEncoder.pbkdf2()` | How stored hashes are checked |
| `realm(String)` | `Flash` | The realm the challenge names; clients show it when they ask for a password |
| `cacheFor(Duration)` | 5 minutes | How long a verified user name and password are remembered |
| `cacheSize(int)` | 10,000 | How many pairs are remembered; `0` turns the cache off |

Only TLS keeps the password secret: Basic sends it, base64-encoded, with every request.

## Authentication

A request with `Authorization: Basic <base64 of user:password>` is checked; the scheme name is
matched in any case. The credentials are decoded as UTF-8 and split at the first `:`, so a password
may contain `:`. A request without a Basic header is not this mechanism's and goes on to the next.

| Request | Result |
|---|---|
| Known user, right password | The account's `Principal` |
| Unknown user, wrong password, invalid base64, no `:` | `401` with the challenge |

An unknown user costs a password check too, against a hash made at install, so the answer takes as
long as for a wrong password.

## Challenge

`schemes()` returns `SecurityScheme.basic(realm)`. Its challenge is
`Basic realm="<realm>", charset="UTF-8"`, sent in `WWW-Authenticate` with every `401` from
security-core, alongside the challenges of the other mechanisms.

## Cache

A password hash is slow on purpose (PBKDF2 with 600,000 iterations by default), and a Basic client
sends the password with every request. `BasicAuth` therefore remembers each verified pair:

- The key is the SHA-256 of `user:password`; the value is the verified `Principal`.
- An entry lasts `cacheFor`; the least recently used entry goes when the cache holds more than
  `cacheSize`.
- A wrong password is never remembered.
- A changed or removed account keeps working with the old password until its entry expires.

## OpenAPI

With flash-ext-openapi installed, `SecurityDocs` declares a security scheme named `basic`,
`{"type": "http", "scheme": "basic"}`, and lists it on every protected operation.
