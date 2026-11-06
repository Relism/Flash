# flash-ext-security-saml

SAML 2.0 for Flash, as a service provider: browser sign-in with any number of identity providers,
Single Logout both ways, and the application's metadata for providers to import. It needs
`flash-ext-security-core`.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-security-saml</artifactId>
</dependency>
```

```java
app.install(
        new SecurityModule().origin("https://app.example"),
        new SamlModule(SamlKeys.of(keyPem, certificatePem),
                SamlIdp.of("corp", "https://idp.example.com/metadata")));
```

The identity provider imports `https://app.example/auth/saml/metadata`. Nothing else is configured on
either side.

| Page | Content |
|---|---|
| [docs/setup.md](docs/setup.md) | Keys, the module's options, what the provider needs |
| [docs/sign-in.md](docs/sign-in.md) | Routes, the flow, every check a response passes, the principal |
| [docs/logout.md](docs/logout.md) | Single Logout started by the application or by the provider |
| [docs/providers.md](docs/providers.md) | Metadata, trust, refresh, aggregates, providers registered at runtime |
