# Test helpers

## TestSecurity

A module that authenticates test requests as any `Principal`, whatever other mechanisms the app has.

```java
app.request().with(users.as(principal)).get("/me");
```

`as(principal)` registers the principal under a random token and returns a request customiser that
sends `Authorization: Test <token>`. Install the `TestSecurity` instance in the app under test, as a
module. It works because the app runs in the test's JVM, so the principal is passed by reference.

## FakeOidcProvider

An OpenID provider on `127.0.0.1` and a random port, with discovery, keys and a token endpoint for
the authorization code and refresh flows. Sign-in is approved immediately.

```java
try (FakeOidcProvider idp = new FakeOidcProvider().signInAs("alice", Map.of("email", "a@x.io"))) {
    OidcProvider provider = OidcProvider.of("test", idp.issuer(), "app", "secret");
    ...
    request.with(idp.bearer("alice", Map.of("scope", "orders:read")))
}
```

| Method | Effect |
|---|---|
| `issuer()` | The provider's URL |
| `signInAs(subject, claims)` | Who sign-in and refresh vouch for. The client id is always `app` |
| `expiresIn(seconds)` | Lifetime of issued tokens. `0` expires a session at once |
| `client(secret, redirectUris...)` | From now on refuses other secrets or redirect URIs |
| `token(subject, claims)` | A signed access token (`typ: at+jwt`), valid five minutes |
| `idToken(subject, claims)` | A signed ID token, which a resource server must not take for an access token |
| `bearer(subject, claims)` | A request customiser sending a bearer `token` |
| `close()` | Stops the server |

Because the fake runs on a loopback `http` address, use `OidcModule.allowLocalProviders()` only when
registering such a provider at runtime; configured providers are not restricted.

## FakeSamlIdp

A SAML identity provider on `127.0.0.1` and a random port, serving its metadata at `metadataUrl()`.
Sign-in is approved immediately: `respond(location)` answers the request the application redirected the
browser to with the `SAMLResponse` the browser would post back.

```java
FakeSamlIdp.Credential sp = FakeSamlIdp.Credential.generate();
try (FakeSamlIdp idp = new FakeSamlIdp().signInAs("alice", Map.of("groups", List.of("admin")))) {
    SamlModule saml = new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()), SamlIdp.of("test", idp.metadataUrl()));
    ...
    FlashResponse login = app.get("/auth/saml/test/login");
    String response = idp.respond(login.header("Location"));
    // POST SAMLResponse=<response> to /auth/saml/acs with login's flash_saml cookie
}
```

| Method | Effect |
|---|---|
| `Credential.generate()` | A new RSA 2048 key and self-signed certificate in PEM, made with the JDK's `keytool` |
| `new FakeSamlIdp(credential)` | A provider signing with that credential. Without one, a new credential |
| `metadataUrl()`, `entityId()`, `metadata()` | Its metadata: SSO and logout by HTTP-Redirect, its signing certificate |
| `signedMetadata(validUntil)` | The metadata signed with its key, valid until `validUntil` when not `null` |
| `signInAs(nameId, attributes)` | Who sign-in vouches for, as a persistent `NameID` |
| `sign(response, assertion)` | Which elements are signed. Both by default |
| `encryptTo(certificatePem, algorithm)` | Encrypts the assertion with RSA-OAEP and that data algorithm URI. `null` stops |
| `rewrite(operator)` | Rewrites each response before it is signed: a provider configured wrongly |
| `respond(location)` | The `SAMLResponse` answering the request in a login redirect |
| `unsolicited(acs, audience)` | A `SAMLResponse` answering no request |
| `logoutRequest(slo, nameId)` | The application's logout URL carrying a signed `LogoutRequest`, `RelayState=relay` |
| `logoutResponse(location, slo)` | The application's logout URL carrying a signed `LogoutResponse` to the request in `location` |
| `redirected(location, parameter)` | The XML a redirect URL carries |
| `close()` | Stops the server |

## OAuthTestClient

Drives the app's own authorization server as an OAuth client would: discovery, registration,
authorization code with PKCE, consent and token exchange.

```java
OAuthTestClient.Tokens tokens = OAuthTestClient.register(app, users).authorize(() -> "alice");
app.request().with(tokens.bearer()).post("/mcp");
```

`register(app, users)` registers a public client allowed refresh tokens at the registration
endpoint. `authorize(user)` and `authorize(user, extraParameters)` sign the user in through
`TestSecurity`, consent if asked, and exchange the code. `refresh(refreshToken)` trades a refresh
token. `Tokens` holds `accessToken`, `refreshToken`, `scope` and `bearer()`.

## OidcTokens

`OidcTokens.passwordGrant(issuer, clientId, clientSecret, username, password)` obtains a real access
token from a real provider (Keycloak in Testcontainers, typically) with the resource owner password
grant, and returns a customiser sending it. The client must allow direct access grants, which is a
test-realm setting.
