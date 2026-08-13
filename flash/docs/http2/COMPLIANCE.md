# HTTP/2 compliance

This document records the repeatable protocol gate for Flash's HTTP/2 server. The automated
matrix runs from Maven; external tools are selected through system properties so local builds
without them skip only the corresponding interoperability adapter. CI installs and enables every
command-line client listed below.

## h2spec

Validated on 2026-08-13 with h2spec 2.6.0.

| Listener | Cases | Failures | Skips |
|---|---:|---:|---:|
| TLS with ALPN `h2` | 146 | 0 | 0 |
| Cleartext prior knowledge on the mixed HTTP/1.1 + HTTP/2 port | 145 | 0 | 0 |

`H2SpecComplianceTest` parses h2spec's JUnit XML and fails on a failure, error, or skipped case.
The cleartext selection omits only `http2/3.5/2`, which sends a complete invalid HTTP/2 preface.
That case assumes a dedicated HTTP/2 endpoint. Flash deliberately has one cleartext port that
selects HTTP/2 only when the 24-byte prior-knowledge preface matches; any other initial bytes are
HTTP/1.1 input. RFC 9113 section 3.3 defines the exact preface as the cleartext protocol selector,
while section 3.4's `PROTOCOL_ERROR` applies after an endpoint is operating as HTTP/2. The HTTP/2
state machine itself does return `GOAWAY(PROTOCOL_ERROR)` for a complete invalid preface, covered
byte-for-byte by `invalid-preface.hex`. Excluding the mixed-port negotiation case therefore does
not waive an HTTP/2 state-machine requirement.

## Interoperability

Automated results recorded on 2026-08-13:

| Client | Version | Mode and coverage | Result |
|---|---|---|---|
| curl | 8.5.0, libnghttp2 1.59.0 | TLS and h2c; GET, POST, 2 MiB upload/download | pass |
| Java `HttpClient` | Temurin 21.0.11+10 | TLS; GET, POST, large bodies and multiplexing | pass |
| nghttp | nghttp2 1.59.0 | TLS and h2c; verbose SETTINGS/HEADERS/DATA trace, POST and 2 MiB download | pass |
| grpcurl | 1.9.3 | h2c; unary, server-streaming, client-streaming, bidi and error trailers | pass |

The 1,000-stream test uses one TCP connection and admits at most the advertised 64 live streams
at once. This tests 1,000 multiplexed stream lifecycles without contradicting
`SETTINGS_MAX_CONCURRENT_STREAMS` or weakening the production memory bound.

Chrome and Firefox are a release smoke test rather than a CI dependency. For each release, record
the exact stable browser versions and date in the release evidence, then verify:

1. Load a TLS route and confirm `h2` in the browser network protocol column.
2. Exercise GET, POST, a large upload and a large streamed download.
3. Open the same registered WebSocket route over HTTP/1.1 and RFC 8441, exchange a fragmented
   message larger than one flow-control window, and close from each side once.
4. Confirm no certificate, console, failed-request, or retry-to-HTTP/1.1 warnings.

This manual row is intentionally not represented as an automated pass: browser release testing
must record the browsers actually shipped at release time rather than a stale development image.

## Fuzzing and regression corpus

All fuzz targets use deterministic xorshift or `Random` seeds, fixed maximum input lengths, an
absolute JUnit time budget, and a post-GC retained-heap assertion. Untyped runtime failures fail
the test immediately. The permanent targets cover:

| Target | Cases | Seed |
|---|---:|---|
| frame reader | 10,000,000 | `0x485532445f465a32` |
| HPACK decoder | 10,000,000 | `0x75419113c0de` |
| Huffman decoder | 1,000,000 | `0x7541485546464d4e` |
| pseudo-header validator | 250,000 | `0x911350534555444f` |
| HTTP/1 request parser | 25,000 | `0x911248545450314c` |

Exact wire inputs for implementation defects live under
`src/test/resources/http2/regressions/`; `Http2RegressionCorpusTest` executes every file and
asserts the terminal frame and error code. The nightly `Http2SoakTest` defaults to ten minutes of
GET, POST, streaming DATA, reset and PING traffic, with retained-heap assertions. A short run can
be requested with `-Dflash.http2.soak=true -Dflash.http2.soak.seconds=10`.

## Deliberately absent features

- HTTP/2 server push is not exposed. A client cannot send `PUSH_PROMISE` to a server (RFC 9113
  section 6.6); receiving one is a connection error. Flash does not originate push.
- RFC 7540 dependency-tree priority scheduling is not implemented. RFC 9113 section 5.3.2
  deprecates the scheme; PRIORITY frames are validated and ignored as required.
- `Upgrade: h2c` is not implemented. RFC 9113 section 3.1 removed the HTTP/1.1 upgrade mechanism;
  cleartext support uses section 3.3 prior knowledge.

These omissions do not create alternate request/response APIs: HTTP/1.1 and HTTP/2 remain peers
behind the transport protocol boundary.
