# Providers

## Metadata

Everything about a provider comes from its metadata: where users sign in and out, the bindings and the
signing keys. No provider needs settings of its own. Requests to it are always signed.

| Read from the metadata | Use |
|---|---|
| `entityID` | Matches a response's `Issuer` to the provider. Two providers cannot share one |
| `IDPSSODescriptor` supporting SAML 2.0 | Required |
| `SingleSignOnService` | HTTP-Redirect, else HTTP-POST. Required |
| `SingleLogoutService` | HTTP-Redirect. `ResponseLocation` when set, for responses |
| `KeyDescriptor` with `use="signing"` or no `use` | Every certificate in it verifies signatures, in any order, so a provider can roll keys over |
| `shibmd:Scope` | Scopes its users' `subject-id` and `pairwise-id` may carry. Regular expressions are ignored |
| `validUntil` | The earliest on the way from the root to the entity. Past it, the metadata is refused, and nothing the provider signs is trusted |

Every endpoint must be an absolute `https` URL, or `http` for a configured provider: the browser is sent
there by a `Location` header or a form this origin serves, so anything else is refused. Characters
beyond ASCII go out percent-encoded.

A certificate only carries its key: its validity dates and issuer are not checked. A key weaker than RSA
2048 or EC 256 bits is left out; a provider left with none is refused.

## Trust

| Configured as | Trusted because |
|---|---|
| `SamlIdp.of(id, url)` | It came over that URL. Use https |
| `SamlIdp.fromXml(id, xml)` | The operator configured it |
| either, with `metadataSigner(certificatePem)` | Its root is signed by that certificate. It must then have a `validUntil`, at most a year away |

## Fetching and refreshing

Metadata from a URL is fetched when the server starts. The connection has 5 seconds, the response
headers 10, the whole download 30. Up to 5 redirects are followed, never from https to http. The body
may be at most 1 MiB for a provider registered at runtime and 256 MiB for a configured one, which may
be a federation's aggregate. A provider whose metadata cannot be read or does not describe a usable
provider fails the boot and names it.

Every 5 minutes, metadata older than an hour, or within 10 minutes of its `validUntil`, is fetched
again, each provider on its own thread. A failed refresh is logged at WARN and the last metadata stays in use until
it expires.

## Aggregates

A federation publishes many entities in one `EntitiesDescriptor`. Name the provider to take:

```java
SamlIdp.of("uni", "https://federation.example/metadata.xml")
        .entityId("https://idp.uni.example/idp/shibboleth")
        .metadataSigner(federationCertificatePem)
```

Nested `EntitiesDescriptor`s are searched.

## Providers at runtime

For an application where each customer connects their own provider:

```java
saml.register(SamlIdp.of("acme", "https://idp.acme.com/metadata"));
saml.unregister("acme");
```

`Saml` is the service `SamlModule` installs; take it as a constructor parameter, or read
`samlModule.saml()` before start.

| Method | Behavior |
|---|---|
| `register(provider)` | Reads its metadata now and trusts it. Fails with `IllegalArgumentException` when the id belongs to a configured provider, another provider has its entity ID, the metadata is unusable, or it is fetched over anything but https from a public address, redirects included; with `IllegalStateException` when the metadata cannot be fetched or parsed |
| `unregister(id)` | Its responses are refused from now on, and its users' logouts stay local |

A registered provider serves `/auth/saml/{id}/login` and the discovery route, but is not listed at
`/auth/methods`: which provider a user signs in with is the application's decision.

A registered provider is trusted for what it asserts, and nothing proves it owns the entity ID it
declares. Before registering one for a customer, decide what its users may become: key them by
`issuer()` and `name()`, and do not take roles from its attributes with `ClaimRoles` unless the
customer's own roles are what it grants.

`SamlModule.allowLocalProviders()` lifts the address rule for development. Otherwise registering a
provider would be a request forgery against the server's own network.

## Discovery

With several providers, a login page lists them from `/auth/methods`. An external discovery service
(SAML IdP Discovery) returns the user to `<origin>/auth/saml/login?entityID=<provider>`; pass
`redirect` the same way as on the other login route.
