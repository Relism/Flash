# Helpers

## Tokens

`Tokens.random(bytes)` returns that many bytes from a `SecureRandom`, base64url encoded without
padding. Use it for session ids, state values and other unguessable identifiers.

## Redirects

`Redirects.localPath(path)` returns `path` if it is a same-origin path, otherwise `/`. It refuses an
absolute URL, a protocol-relative `//host` and `/\host`. Use it before redirecting to a user-supplied
`redirect` parameter.

## PublicUrl

`PublicUrl.require(url)` checks that a URL someone else chose is safe to fetch. It throws
`IllegalArgumentException` unless the scheme is `https` and every address of the host is public:
loopback, site-local, link-local, wildcard, multicast and IPv6 unique-local addresses are refused.
The address is resolved here and again by the HTTP client, so DNS rebinding between the two is not
covered.
