# flash-ext-security-basic

HTTP Basic authentication (RFC 7617) over the same accounts and password hashing as
[flash-ext-security-form](../flash-ext-security-form/README.md). For clients that can send nothing
else, such as an operating system mounting a [WebDAV](../flash-ext-webdav/README.md) share. Needs
`flash-ext-security-core`, and TLS.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-security-basic</artifactId>
</dependency>
```

```java
app.install(new SecurityModule(), new BasicAuth(accounts::byUsername));
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Options, the challenge, the cache in front of the password hash, OpenAPI |
