# The message model

Audience: contributors. This is the design record for `dev.relism.flash.http`'s shared
request/response model: what is pooled, what that pooling means for callers, and how HTTP/1.1 and
HTTP/2 retain the same public contract.

## Why this exists

Through Phase 5, `Request`, `RequestLine`, `RequestBody`, and `Response` were all allocated fresh
per request — `DEC-20` measured this at 120.008 B/op for parse+route alone, and traced 100% of it
to these four objects. Phase 6 pools all of them, following the same "one instance per connection,
repositioned via `reset()`, never reallocated" idiom `Http1HeaderMap` and `RequestLine` already
established in earlier phases. This document is the single place that idiom's contract — and the
hazards of misusing it — is written down for the whole model, instead of being re-derived from
each class's own Javadoc.

## What is pooled, and by whom

```
RequestParser (one per connection)
├── Http1HeaderMap  headerMap    — reset() per request
├── RequestLine     requestLine  — reset() per request
├── Request         request     — reset() per request (via Request.forParsed)
├── RequestBody     requestBody — reset() per request
├── RequestByteView pathView     — reset() per request                    (EX-42)
├── RequestByteView queryView    — reset() per request, only when present (EX-42)
└── RequestByteView protocolView — reset() per request                    (EX-42)

Http1Connection (one per connection)
└── Response pooledResponse — reset() per request (unless a handler returns its own Response)

Router.Scratch (one per connection, via Router#newScratch)
└── PathParams pathParams — reset() per matched request (see BYTES.md, EX-19)
```

Every one of these follows the same three rules:

1. **One instance per connection**, created once (in `RequestParser`'s or `Http1Connection`'s
   constructor, or in `newScratch()`), never re-allocated for the connection's lifetime except a
   backing array growing to a new high-water mark (e.g. `RequestParser.buffer` doubling, or
   `Router.Scratch.ensureParamCapacity`).
2. **`reset(...)` repositions, it does not allocate** — the method that transitions the instance
   from "describes request N" to "describes request N+1".
3. **Do not retain past the handler.** A reference captured in a closure, a `CompletableFuture`
   continuation, or a background thread and read after the handler returns will observe whatever
   the *next* request repositioned the instance to — silently, unless the dev-mode guard below
   catches it.

## The dev-mode use-after-recycle guard (`Request`, `Response`)

`Request` and `Response` — the two objects most likely to be captured by user code — additionally
track an `active` flag, set `true` by `reset()` and `false` by `recycle()`. Every public accessor
calls `checkActive()` first:

```java
private void checkActive() {
    if (!active) throw new IllegalStateException("Request used after the handler returned: ...");
}
```

`Http1Connection` calls `recycle()` once the handler and `drain()` have finished only when
`ServerConfig.dev` is on, so in production `active` stays `true` and the check is one predictable
branch, while in development a handler that kept a request gets a precise `IllegalStateException`
at the exact misusing call site. The switch lives on the connection, not in a static flag, so a
test exercises both modes by calling `recycle()` itself.

`RequestBody`, `RequestLine`, `Http1HeaderMap`, and `PathParams` do **not** carry this guard: they
are reached only through `Request`/`Response` (or, for `PathParams`, through `Request.param`),
so `Request`/`Response`'s own guard already catches a stale read before it would reach these.

## `RequestBody`: two read modes, one reused bounded stream

`RequestBody.stream()` and `.bytes()` are mutually exclusive per request (calling both is
undefined). `EX-23`/`EX-24` (Phase 6) replaced two allocation sources in the streaming path:

- `stream()` used to build a fresh `SequenceInputStream` + `ByteArrayInputStream` + anonymous
  bounded `InputStream` on every call. It now repositions one persistent
  `BoundedBufferedInputStream` (a private inner class) via `reset(preBuf, preBufOff, preBufLen,
  socketRemaining)` — the same object is returned every time, just pointed at different bytes.
- `drain()`'s chunked-body path used to call `InputStream.transferTo`, whose default
  implementation allocates a fresh 8 KiB `byte[]` on every call. It now drains through a lazily
  created (only if a chunked body is ever actually drained), persistent `drainBuffer`.

`RequestBody.of(byte[])`/`.empty()` remain as freestanding, unpooled factories for test/manual
construction (mirroring `Request`'s own manual constructor) — production's only pooled instance is
the one `RequestParser` owns.

## `Response`: byte-level headers, one write, `ResponseSerializer` as the source of truth

Before Phase 6, `Response.header(String, String)` stored headers as `List<byte[]>` — one `String`
concatenation and one `byte[]` allocation per call. `Response` now stores structured headers in a
`ByteWriter`-backed name/value region plus parallel `int[]` quads (`nameOff, nameLen, valOff,
valLen`), written via `ByteWriter.writeAscii` — zero-allocation on a warm connection.
`header(String, long)` writes a number's digits straight into the same region, with no `String`.

`PreEncodedHeader` precomputes a header's name and value ASCII bytes once (for example, a constant
response header set at boot). Preserving the boundary lets HTTP/1.1 render a field line and HTTP/2
encode the same pair through HPACK without a second public header type.

`ResponseSerializer.forEachField(Response, FieldConsumer)` is the **one source of truth for what
headers a response has** — it enumerates `Content-Type` (if set) plus every structured custom
header, in order, and is the only place that knowledge lives. `Http1ResponseWriter` renders that
sequence as `Name: Value\r\n` lines; `Http2ResponseWriter` renders the same sequence as HPACK.
Deliberately excluded: `Content-Length`/`Connection`/`Date` (connection framing, not response
object properties — and HTTP/2 has no `Connection` header at all, RFC 9113 §8.2.2).

`Http1ResponseWriter` (`EX-27`) serializes the entire response head — status line, `Content-Type`,
`Date`, every custom header, `Content-Length`/`Connection` — into
`ConnectionScratch.responseHead` (a reused `ByteWriter`) and issues **one** `OutputStream.write`
call for the head plus any body at or below `Http1Limits.INLINE_BODY_THRESHOLD` (8 KiB), instead
of roughly ten small writes. A larger body is written in a second `write` call right after — folding
it into the head buffer first would cost an extra full-body `memcpy` the syscall reduction does not
pay for. Streaming/chunked bodies write the head, then relay their own bytes as they arrive, by
definition too large or unbounded to fold into one buffer up front.

`Response.header(...)` (any overload) is bounded by `Http1Limits.MAX_RESPONSE_HEADER_BYTES`/
`MAX_RESPONSE_HEADER_COUNT` (`EX-43`) — unlike every other `Http1Limits` constant, this guards
against a bug in the *caller* (a handler looping over an unbounded collection while building
headers) rather than a hostile peer: since `Response` is now pooled per connection, an unbounded
`headerRegion` would otherwise grow for the rest of the connection's lifetime, never shrinking
back down between requests. Both checks throw `IllegalStateException`, not
`MalformedRequestException` — this is an application-code misuse, not a wire-input rejection.

## `HeaderView` / `Http1HeaderMap` (`DEC-22`)

`HeaderMap` split into `HeaderView` (the protocol-neutral read contract: `first`, `all`, `view`,
`valueEqualsIgnoreCase`, `contains`, `count`, `forEach`) and `Http1HeaderMap` (the existing
byte-buffer-backed implementation, kept in `dev.relism.flash.http` rather than moved to
`dev.relism.flash.http1`: `RequestParser` (root package) owns and constructs it, and
`http1`→root already exists via `Http1Connection`, so moving it to `http1` would create a
`models`↔`http1` package cycle). `RequestLine.headers` is typed as the
interface; `Http2HeaderMap` is the HPACK-backed second implementation without requiring a
`Request` or `RequestLine` API split.

## `ByteTemplate` (`EX-28`)

Off the h1 request/response hot path (used only by `ErrorPages`, on 404/500), but in scope because
it was a clean instance of the "precompute at boot" category the phase's own text calls out.
`render(String...)` used a nested loop — for every key-value pair, scan every slot — to find
matching placeholders, and a repeated placeholder name (`{{var}} == {{var}}`) meant a naive
name→single-index map would be wrong. Fixed by mapping each slot name to the (usually
one-element) array of every slot index using that name, built once at construction. A new
`renderInto(byte[], int, String...)` overload writes into a caller-supplied buffer and returns the
length written, for future callers with a reusable scratch buffer available; `render(String...)`
keeps its allocating signature for compatibility.

## `Multipart` (`EX-29`, and `EX-38`–`EX-41`)

Audited per the plan's mandatory rules for any file over 300 lines. Findings and fixes: an eagerly
buffered part body (text fields, and — during a full `parts()`/`parts(String)` scan — file bodies
too) had no size bound (`EX-38`, fixed with a bounded read capped by
`Http1Limits.MAX_MULTIPART_BUFFERED_PART_SIZE`); the part count was unbounded (`EX-39`, capped by
`Http1Limits.MAX_MULTIPART_PARTS`); per-part header parsing had neither a header-count nor a
line-length bound (`EX-40`, capped by `Http1Limits.MAX_MULTIPART_PART_HEADER_COUNT`/
`MAX_MULTIPART_HEADER_LINE_LENGTH`); the multipart boundary's length was checked and found to
already be bounded transitively, via `Http1Limits.MAX_HEADER_VALUE_LENGTH` on the `Content-Type`
header it comes from (`EX-41`, a non-finding, recorded so "checked, found fine" isn't mistaken for
"wasn't checked"). None of these bounds apply to `Part.materialize()` on a streaming file part
returned by `Multipart.file()` — that call is documented as an explicit, opt-in heap allocation the
caller chooses to pay for, the same way `RequestBody.bytes()` is.

## `EX-42`: the last per-request allocation, found by re-measuring

Pooling `Request`/`RequestBody`/`RequestLine`/`Response` dropped `RequestPipelineBenchmark`'s
`parseAndRoute` from 120.008 B/op to 48.008 B/op — real progress, but not the 0 B/op the phase's
own DoD text requires. Reading `RequestParser.parse` turned up three `new
FastPathViews.RequestByteView(...)` allocations (path, query when present, protocol) on every
call — pre-existing since at least Phase 4, invisible until the larger `Request`/`RequestBody`/
`RequestLine` cost sitting on top of them was removed. Fixed the same way as everything else in
this document: `RequestByteView` gained a `reset(byte[], int, int)`; `RequestParser` now owns one
pooled instance per role. `parseAndRoute` measures 0.008 B/op after the fix — JMH's noise floor,
effectively 0.

## The zero-alloc contract, closed

> A complete h1 request/response cycle on a warm connection — parse, route with path params, read
> three headers, set two response headers, write a 200 with a `byte[]` body — must be 0 B/op.

`RequestPipelineBenchmark.parseAndRoute` (parse + route with a parametric match, no header/param
access) measures 0 B/op. `parseRouteAndExtractThreeFields` (the same, plus one path param and two
header reads) measures 184.009 B/op — entirely the `String` allocations the contract's own text
exempts ("except for the user-facing `String`s the handler explicitly asks for").
