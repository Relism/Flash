# Setup

## Keys

The application signs its requests and decrypts assertions with an RSA key of at least 2048 bits,
given as a PKCS#8 private key and an X.509 certificate in PEM. The certificate only carries the key,
so a self-signed one is enough:

```
openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj "/CN=app.example" -keyout sp.key -out sp.crt
```

```java
SamlKeys keys = SamlKeys.of(Files.readString(Path.of("sp.key")), Files.readString(Path.of("sp.crt")));
```

`SamlKeys.of` fails with `IllegalArgumentException` when the key is not PKCS#8 RSA, is shorter than
2048 bits, or does not belong to the certificate.

To rotate, put the new key first and keep the old one with `and`:

```java
SamlKeys.of(newKey, newCertificate).and(oldKey, oldCertificate)
```

The first key signs and is the only one published in the metadata. Every key decrypts, so assertions
encrypted to the old key while providers pick up the new metadata still sign in. Drop the old key once
they have.

## Origin

`SecurityModule.origin` is required: the boot fails without it. The entity ID
(`<origin>/auth/saml/metadata`), the assertion consumer URL and the audience every assertion is checked
against are built from it, never from the request's `Host` header.

## SamlModule

```java
new SamlModule(keys, SamlIdp.of("corp", metadataUrl).name("Corp SSO"))
        .info("Acme", "https://app.example/privacy", "ops@app.example")
        .logo("https://app.example/logo.png", 80, 60);
```

| Method | Default | Meaning |
|---|---|---|
| `info(displayName, privacyStatementUrl, technicalContactEmail)` | none | Published in the metadata. Federations require them; a provider configured by hand does not. Any of them may be `null` |
| `logo(url, width, height)` | none | Published in the metadata. An https URL or a data URI |
| `clockSkew(Duration)` | 3 minutes | Tolerated difference between this server's clock and a provider's |
| `algorithms(String... uris)` | see below | Replaces the accepted XML Signature and Encryption algorithms |
| `allowLocalProviders()` | off | Lets providers registered at runtime use http and private addresses. Development only |
| `saml()` | | The `Saml` service, for code that runs before the app starts |

Accepted algorithms by default:

| Use | Algorithms |
|---|---|
| Digest | SHA-256, SHA-384, SHA-512 |
| Signature | RSA and ECDSA with SHA-256, SHA-384, SHA-512 |
| Data encryption | AES-GCM and AES-CBC, 128, 192 and 256 bits. CBC logs a warning once: ask the provider for GCM |
| Key transport | RSA-OAEP (`xmlenc#rsa-oaep-mgf1p` and `xmlenc11#rsa-oaep`) |

SHA-1 signatures and RSA PKCS#1 v1.5 key transport are refused.

AES-CBC is accepted because some providers encrypt with it by default, but it is open to padding oracle
attacks: when a provider signs only the assertion, the encrypted assertion is decrypted before any
signature is checked. Configure providers for AES-GCM where they allow it, or leave CBC out with
`algorithms(...)`, and rate-limit `POST /auth/saml/acs` with `flash-ext-limiter`.

## SamlIdp

| Method | Default | Meaning |
|---|---|---|
| `SamlIdp.of(id, metadataUrl)` | | Metadata fetched at start and refreshed. See [providers.md](providers.md) |
| `SamlIdp.fromXml(id, metadataXml)` | | Metadata handed over as a document |
| `name(String)` | the id | What `/auth/methods` shows |
| `entityId(String)` | none | The provider to take from an aggregate |
| `metadataSigner(String certificatePem)` | none | Trust the metadata for its signature. See [providers.md](providers.md) |
| `authnContext(String... classRefs)` | none | Request one of these contexts, compared exactly, and refuse an assertion with another |
| `unsolicited()` | off | Accept sign-ins the provider starts on its own. See [sign-in.md](sign-in.md#unsolicited-responses) |
| `signedResponses()` | off | Refuse a response whose envelope is not signed, even when its assertion is |

The `id` is letters, digits, `_` and `-`. It appears in routes and in `SamlPrincipal.idp()`.

## What the provider needs

Give the provider's administrator the metadata URL, `<origin>/auth/saml/metadata`. If their product
asks for values instead:

| Field | Value |
|---|---|
| Entity ID, audience | `<origin>/auth/saml/metadata` |
| Assertion consumer service (ACS, reply URL) | `<origin>/auth/saml/acs`, HTTP-POST |
| Single logout service | `<origin>/auth/saml/slo`, HTTP-Redirect |
| Signing and encryption certificate | The first certificate of `SamlKeys` |
| NameID format | Any. A persistent one, or a `subject-id` or `pairwise-id` attribute, keeps the user's identity stable |
