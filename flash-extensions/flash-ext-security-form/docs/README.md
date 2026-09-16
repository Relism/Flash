# flash-ext-security-form

Password sign-in for [`flash-ext-security-core`](../../flash-ext-security-core/docs/README.md).

```java
app.install(new SecurityExtension())
   .install(new FormLoginExtension(username -> accounts.find(username)));   // PasswordStore
```

`POST /auth/form/login` takes `username` and `password` form-encoded, starts a session and answers
`303` to `?redirect=` (same-origin paths only) or `/`. A wrong password and an unknown account are the
same `401`, and cost the same time. The method is listed at `/auth/methods` with `"kind":"form"`, and
the entry point sends browsers to `SecurityExtension.loginPage` to render it.

`PasswordEncoder.pbkdf2()` hashes (PBKDF2-HMAC-SHA256, 600k iterations, JDK only); use it to create
accounts, or pass another encoder to `passwordEncoder(...)`. Rate-limit the route with
`flash-ext-limiter`.
