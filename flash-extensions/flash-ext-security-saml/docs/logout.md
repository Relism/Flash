# Logout

Single Logout uses the HTTP-Redirect binding at `GET /auth/saml/slo`, and every message both ways is
signed. On a redirect the signature is checked over the query parameters exactly as they arrived. An
ECDSA signature is accepted in either encoding providers use: `r` and `s` side by side, as XML Signature
writes them, or DER. A provider without a `SingleLogoutService` for HTTP-Redirect in its metadata gets no logout
messages: signing out ends the local session only.

## Started by the application

`POST /auth/logout` (see `flash-ext-security-core`) ends the local session first, then answers `303` to
the provider that signed the user in, found by its entity ID, with a `LogoutRequest` built and signed
at that moment. It names the user with the exact
`NameID` the provider sent, qualifiers included, and the `SessionIndex`. A user identified without a
`NameID` cannot be named, and the logout stays local.

The provider answers with a `LogoutResponse` to `/auth/saml/slo`. Its signature, issuer, `Destination`
and `IssueInstant` are checked, and the browser is sent to `/`.

## Started by the provider

A `LogoutRequest` at `/auth/saml/slo` must be signed by a known provider, have this service as
`Destination`, have an `ID`, have been issued within the last 5 minutes (plus the clock skew), and not
be past its `NotOnOrAfter`. The same holds for a `LogoutResponse`. The session in this browser's cookie ends when its
user was signed in by that provider with the `NameID` the request names, and with one of its
`SessionIndex` values when it lists any. The application then answers with a signed `LogoutResponse`
of status `Success`, carrying the `RelayState` back.

A request that fails a check is a `400` with `Logout could not be completed`; the reason is logged at
WARN.
