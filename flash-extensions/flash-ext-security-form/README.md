# flash-ext-security-form

Password sign-in for Flash: `POST /auth/form/login` with `username` and `password`, PBKDF2 password
hashing, and a session on success. Needs `flash-ext-security-core`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-form</artifactId>
</dependency>
```

```java
app.install(new SecurityModule(), new FormLoginModule(username -> accounts.find(username)));
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | The route, the store, password hashing |
