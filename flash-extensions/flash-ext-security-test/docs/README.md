# flash-ext-security-test

Test-scope utilities for applications on `flash-ext-security-core`.

```java
FlashTest app = FlashTest.of(flash -> flash.apply(new MyApp()).install(new TestSecurity()));

app.request().with(TestSecurity.as(() -> "alice")).get("/me");                 // any principal
app.request().with(TestSecurity.as(new OidcPrincipal(...))).get("/projects");   // a mechanism's own type
```

`TestSecurity.as(principal)` hands the principal to the application by reference — `FlashTest`
serves it in the same JVM — so tests exercise the real `UserResolver`, `RoleResolver` and policies
with no identity provider running. Tests of the flows themselves use the mechanism's own kit:
`FakeOidcProvider` and `OidcTokens` in this module for OIDC, a real `POST` for form login.
