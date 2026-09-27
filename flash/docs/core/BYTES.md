# The byte layer

Audience: contributors. This is the design record for `dev.relism.flash.bytes` — the
protocol-neutral byte primitives both HTTP/1.1 and HTTP/2 build on — and for the Phase 4
allocation/scanning fixes (`EX-04`, `EX-05`, `EX-09`, `EX-19`, `EX-25`, `EX-26`, `EX-33`, plus
`EX-06`'s router half) that consume them.

## Why this exists

Before Phase 4, byte-scanning and case-insensitive comparison logic was duplicated, slightly
differently, in `RequestParser`, `HeaderMap`, and `Http1KeepAlive`; `fpr-core`'s word-at-a-time
router-matching fast path (`ByteCompare`) was wired up but never actually enabled anywhere
(`EX-04` — every `ByteView` implementation returned `supportsLong() == false`); and four call
sites allocated a fresh view, array, or `String` per call on paths a realistic middleware chain
hits 6–10 times per request. `dev.relism.flash.bytes` is the single home these fixes converge on,
so no later phase (HPACK, the frame layer) has to invent its own scanning primitives.

## Package layout

```
dev.relism.flash.bytes
├── ByteScan                 static scanning/comparison/hashing utilities, scalar + SWAR
├── ArrayBackedByteView       capability interface: a ByteView backed by one contiguous byte[]
├── SegmentedByteView         the deliberate non-array-backed case (K discontiguous segments)
├── PooledSlice                reusable ArrayBackedByteView, the EX-05 fix
├── SlicePool                   a small fixed-size ring of PooledSlice
├── ByteWriter                 index-based writer into a growable byte[] scratch buffer
└── Pairs                       the (hi<<32)|lo allocation-free pair-return idiom, named
```

## The `ByteView` capability hierarchy

```
ByteView                              (fpr-core)
├── ArrayBackedByteView                capability: array() + offset()
│   ├── FastPathViews.RequestByteView  RequestParser's request-line/header slices
│   ├── FastPathViews.SocketByteView   a bare byte[] (e.g. a WebSocket payload)
│   ├── FastPathViews.StringByteView   a String's UTF-8 bytes
│   └── PooledSlice                    the EX-05 reusable, pool-issued slice
└── (bare ByteView, not array-backed)
    ├── SegmentedByteView               K discontiguous segments (general-purpose; HPACK stays contiguous)
    └── FastPathViews.MethodPathByteView  method bytes + another ByteView, composed
```

Code holding a bare `ByteView` and wanting the fast path when the concrete instance happens to
be array-backed does `instanceof ArrayBackedByteView` and falls back to the byte-at-a-time path
otherwise — see `ArrayBackedByteView`'s own Javadoc. This is used throughout Phase 4's fixes:
`Request.path()`, `PathParams.get()` (`EX-25`), and `QueryParams.decode`'s clean-value fast path
(`EX-26`) all take this shape.

## `EX-04`: the `supportsLong()`/`longAt()` contract

`fpr-core`'s `ByteCompare` (decompiled from `fpr-core-1.1.1`, since no source jar is published)
reads its comparison word via
`MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN)` and only takes the
word-at-a-time branch when the caller passed `useLong = true`, comparing the result bit-for-bit
against whatever `ByteView#longAt` returns. The contract this imposes on any `longAt`
implementation:

- Return the same value `LONG_VIEW.get(array, pos)` would, for the identical 8 bytes — meaning
  **little-endian**, fixed, regardless of the host's native byte order (unlike `ByteScan`'s own
  SWAR internals, which use `ByteOrder.nativeOrder()` for speed — see below for why that's a
  different, safe choice in a different context).
- The caller (`ByteCompare`) never calls `longAt(i)` without first establishing `i + 8 <=
  length()` — so `longAt` implementations do not re-check this themselves (a defensive check
  would be dead code on every real call path).

`FastPathViews.RequestByteView`/`SocketByteView`/`StringByteView` implement this;
`MethodPathByteView` (composite, no single backing array) and `SegmentedByteView` (genuinely
discontiguous) both stay at the inherited `false` default — a word-at-a-time read is not merely
unimplemented for these, it is structurally unsound (a read could straddle two sources).

**Verified against `fpr-core` directly** (`FastPathViewsLongAtTest`), not merely by reading
bytecode: `ByteCompare.equals`/`indexOf` called with `useLong=true` and `useLong=false` are
asserted to agree on identical content, on content diverging at every position across an
8+-byte range (word-interior, word-boundary, and scalar-tail cases), and end-to-end through a
real compiled `fpr-core` router with literal route segments ≥ 8 bytes — including a near-miss
route differing only in its last byte, to catch exactly the kind of bounds/endianness bug that
would otherwise silently mis-route a request (the failure mode `EX-04`'s registry entry calls out
by name as the worst possible one here).

## `ByteScan`'s SWAR technique

Both `ByteScan.indexOf` (single byte) and `ByteScan.indexOfCrLfCrLf` (the `\r\n\r\n` header
terminator, `EX-33`) use the classic "does this word contain byte `b`" bit trick: XOR the 8-byte
word against `b` broadcast into every lane, then test for any zero lane with
`(v - 0x0101...01) & ~v & 0x8080...80`. `indexOfCrLfCrLf` uses this as a pre-filter to find a
candidate `CR` byte 8 at a time, then a cheap scalar 3-byte check verifies the full 4-byte match
at each candidate — so a scan touches every byte once per 8-byte stride in the common
no-CR-yet case, rather than once per byte.

This reads the word via `ByteOrder.nativeOrder()`, not a fixed order — safe here (unlike
`EX-04`'s `longAt`) because nothing compares this word against an independently-decoded one;
byte-equality detection itself (finding *that* a matching lane exists) is indifferent to lane
order, and position extraction (`laneIndexOf`) branches on the actual native order once, at
class-init time, to convert a matching bit back into the correct array index either way.

Every SWAR method has a scalar counterpart (`indexOfScalar`, `indexOfCrLfCrLfScalar`) used as
the correctness oracle: `ByteScanTest` property-tests SWAR against scalar at every length 0–256
and every match position (including unaligned starts and matches at the very last valid byte),
and `ByteScanFuzzTest` throws 20 000 fully-random trials at each, per the plan's task 1. All
green — see the class's own Javadoc for the full technique writeup.

## `Http1HeaderMap`'s index

Originally, every `Http1HeaderMap` lookup (`first`, `all`, `view`, `valueEqualsIgnoreCase`)
rescanned the entire header section from scratch — O(n·m) for a realistic middleware chain
performing 6–10 lookups per request. `RequestParser` now populates the index while it validates
each header line; direct `Http1HeaderMap.reset()` callers scan the section exactly once. It records
per-header `(nameOffset, nameLength, valueOffset, valueLength)` and a case-insensitive
32-bit FNV-1a hash of the name (`ByteScan.hashNameIgnoreCaseAscii`) into `int[]` arrays grown
(never shrunk) to the connection's high-water mark, capped by `Http1Limits.MAX_HEADER_COUNT`
(asserted, not silently truncated — the parser rejects a request that would exceed it).
Every lookup then compares the caller's own hash (`ByteScan.hashNameIgnoreCaseAscii(String)`,
computed once) against the index's hashes before ever falling back to a full case-insensitive
name comparison. Production therefore performs one combined validation/index pass rather than
one parse-time pass plus one rescan per lookup. `forEach` uses the same index rather than keeping
an independent scanner.

## `EX-05`: pooled slices

`Http1HeaderMap.view` used to allocate a fresh anonymous `ByteView` (plus its capturing instance)
on every call. It now draws from a small (`VIEW_POOL_SIZE = 4`) `SlicePool` of reusable
`PooledSlice` instances instead. The lifetime contract, restated on the method: **a returned view
stays valid until either the request ends, or `view()` is called `VIEW_POOL_SIZE` more times on
the same map — whichever comes first** — at which point the ring silently repositions the same
object over different bytes. This is a real, demonstrated hazard, not a hypothetical one:
`SlicePoolTest#wraparoundAliasesThePreviouslyReturnedSlice` and `Http1HeaderMapIndexTest` show a
5th call returning the exact same object instance the 1st call did, now aliased to different
content. Path and query parameters have no views: `Request.paramLong`/`queryLong` and friends
parse their bytes in place.

## `EX-19`/`EX-06` (router half): the `Router` scratch

`Router.Scratch` (created once per connection via `Router#newScratch`,
replacing the `ThreadLocal<MatchResult>`/`ThreadLocal<MethodPathByteView>` pair, as an opaque
caller-owned object rather than an extension of `ConnectionScratch`) also owns the reusable
path-param arrays and a single long-lived
`PathParams` instance, grown (via `ensureParamCapacity`, doubling) to the largest param count any
route on that connection has ever matched, and repositioned (`PathParams#reset`) rather than
reallocated on every match. `PathParams` gained a second, count-explicit constructor and a public
`reset(ByteView, int)` specifically for this: the reusable arrays can be larger than a given
request's actual param count, so `count` must be tracked independently of `names.length`.

## `EX-25`/`EX-26`: single-allocation `String` construction

`Request.path()`, `PathParams.get()`, and (for the common "no `%`/`+` in the value" case)
`QueryParams.decode` now build their result `String` directly from the backing array via
`new String(array, offset, length, UTF_8)` when the source is `ArrayBackedByteView`, instead of a
byte-at-a-time copy into a scratch `byte[]` followed by a second allocation for the `String`
itself. `QueryParams.decode` scans the value once for `%`/`+` first; only a value that actually
needs percent-decoding pays for the scratch-buffer path — verified to produce byte-identical
output to the always-decode path it bypasses, across clean values, `+`-only, `%XX`-only, invalid
escapes, and mixed queries (`QueryParamsFastPathTest`).

## Performance measurement

`EX-04` (the router's word-at-a-time path) and `EX-33` (the SWAR header-end scan) both carried an
explicit "measure, and keep only if it doesn't cost" requirement. Both were measured together
with the phase's overall zero-allocation contract in one JMH pass, and both were kept.
