# flash-ext-security-apikey

API keys sent as `Authorization: Bearer <prefix>_<id>.<secret>`. Only the SHA-256 of the secret is
stored. Needs `flash-ext-security-core`.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-security-apikey</artifactId>
</dependency>
```

```java
app.install(new SecurityModule(), new ApiKeys<>("gk", id -> keyRepository.find(id)));
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Generating, storing and checking keys |
