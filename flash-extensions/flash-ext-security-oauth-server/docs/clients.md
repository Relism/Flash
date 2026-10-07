# Clients

A client is described by RFC 7591 metadata. There are three ways one exists.

## Registered by the application

```java
OAuthServer.Registration registration = server.register(Map.of(
        "client_name", "Billing job",
        "grant_types", List.of("client_credentials"),
        "token_endpoint_auth_method", "client_secret_basic"));
// registration.client().id(), registration.secret()
```

This is the only way to get a `client_credentials` client. The secret is returned once and stored
only as a SHA-256 hash. A client with `token_endpoint_auth_method` `none` has no secret.

## Self-registration

`POST /oauth/register` with a JSON metadata body. Enabled by default; turn it off with
`registration(false)`. Self-registered clients may use `authorization_code` and `refresh_token`
only. The answer is `201` with the metadata, `client_id`, `client_id_issued_at` and, for a
confidential client, `client_secret` (`client_secret_expires_at: 0`).

## Client ID metadata documents

A `client_id` that is an `https://` URL is fetched, and the JSON document it returns describes the
client. The document is trusted only if its `client_id` equals that URL, it is at most 16,384 bytes,
and its `token_endpoint_auth_method` is `none` (default). The URL must satisfy
`PublicUrl.require`. Results, including failures, are cached for 10 minutes (up to 1,000 URLs).
`allowLocalClients()` also permits `http` URLs and private addresses, for development.

## Validation

Defaults when absent: `grant_types` `["authorization_code"]`, `response_types` `["code"]`,
`token_endpoint_auth_method` `client_secret_basic`.

| Rule | Error |
|---|---|
| Grants other than `authorization_code`, `refresh_token`, and for the application `client_credentials` | `invalid_client_metadata` |
| `response_types` other than `["code"]` with `authorization_code` | `invalid_client_metadata` |
| Auth method other than `none`, `client_secret_basic`, `client_secret_post` | `invalid_client_metadata` |
| `client_credentials` with auth method `none` | `invalid_client_metadata` |
| No `redirect_uris` with `authorization_code`, or one that is not `https` or an `http` loopback URI, or has a fragment | `invalid_redirect_uri` |

## Redirect URIs

A requested `redirect_uri` must equal a registered one exactly. The exception is loopback
(`127.0.0.1`, `[::1]`, `localhost` over `http`): the port may differ (RFC 8252). With no
`redirect_uri` in the request, a client with exactly one registered URI uses it.
