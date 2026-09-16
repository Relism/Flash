# flash-ext-security-apikey

API keys for [`flash-ext-security-core`](../../flash-ext-security-core/docs/README.md), sent as
`Authorization: Bearer <prefix>_<id>.<secret>`.

```java
ApiKeyExtension<Grant> apiKeys = new ApiKeyExtension<>("gk", id -> rows.find(id));   // ApiKeyStore<Grant>
app.install(new SecurityExtension().roles(...)).install(apiKeys);

GeneratedApiKey key = apiKeys.generate();         // show key.token() once
rows.save(key.id(), key.secretHash(), grant);    // never the token
```

The store returns `ApiKey<G>(id, secretHash, grant, expiresAt, revokedAt)`; `G` is whatever the
application authorizes on. An authenticated caller is an `ApiKeyPrincipal<G>` carrying that grant —
read it in a `RoleResolver` with `identity.principal(ApiKeyPrincipal.class)`.

A bearer token without this prefix is left to other mechanisms; one with it that fails — unknown id,
wrong secret, expired, revoked — is a `401 invalid_token`. Only a SHA-256 of the secret is stored: the
secret is 192 random bits, so a slow KDF would protect nothing and cost every request.
