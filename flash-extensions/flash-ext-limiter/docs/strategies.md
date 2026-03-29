# Rate-limit strategies

Two algorithms are built in. Both are lock-free (CAS-only), operate on pre-allocated
`Bucket` state, and write results into a caller-supplied `long[2]` — zero per-request allocation.

## FIXED_WINDOW

```java
@Limit(strategy = LimitStrategy.FIXED_WINDOW, ...)   // default, can be omitted
guard.limit("ip", 100, 1, TimeUnit.SECONDS)           // default
```

### How it works

The request counter resets to zero at each clock-aligned window boundary.

```
 window 1         window 2         window 3
|────────────────|────────────────|────────────────|
 cnt: 0 1 2 … N  cnt: 0 1 2 … N  cnt: 0 1 2 … N
```

With `requests = 100, window = 1s`:
- Requests 1–100 in a given second → allowed
- Request 101+ in that second → 429, allowed again at second +1

### Implementation

All state is packed into a single `AtomicLong` (`Bucket.slot0`):

```
high 32 bits = reduced epoch  = (currentTimeMs / windowMs) & 0xFFFFFFFF
low  32 bits = request count in the current window
```

One CAS operation per request. At a window boundary the same CAS atomically resets the
counter to 1. No locks, no additional fields.

### Burst behaviour

Because the window is fixed to the clock, a burst can occur at the boundary:
up to `N` requests at the end of window 1 followed immediately by `N` requests at the
start of window 2 → `2N` requests in a short interval.

```
   window 1  │  window 2
 ────────────┼────────────
  99 100 101 │ 1  2  3  4
             ↑ reset: 101 → 429, then 1 is allowed
```

If burst tolerance is unacceptable, use `TOKEN_BUCKET`.

### When to use

- Simple API rate limiting where occasional boundary bursts are acceptable.
- Scenarios where a hard "N requests per clock second/minute" guarantee matters.
- When you want minimal per-bucket memory (one `AtomicLong`, `Bucket.slot1` unused).

---

## TOKEN_BUCKET

```java
@Limit(strategy = LimitStrategy.TOKEN_BUCKET, ...)
guard.limit("ip", 100, 1, TimeUnit.SECONDS, LimitStrategy.TOKEN_BUCKET)
```

### How it works

The bucket holds up to `requests` tokens and refills at a continuous rate of
`requests / window` tokens per millisecond. Each request consumes one token.
A client that was idle accumulates tokens and can fire a burst, but sustained
excess traffic drains the bucket and triggers 429s.

```
tokens
  N ─┐           ┌──── refill slope ────┐
     │           │                      │
  0  └───────────┘ ←─ burst consumed ──→│
      burst here    429s during drain   recovery
```

### Refill rate

`refillPerMs = (requests × 1000) / windowMs`  (integer, minimum 1)

For `requests = 100, window = 1s`:
- Refill rate: 100 tokens/s = 1 token/10 ms
- Max capacity: 100 tokens
- A client idle for 500 ms accumulates 50 tokens and can fire 50 requests instantly.

### Implementation

- `Bucket.slot0` — current tokens × 1000 (fixed-point, avoids floating-point math)
- `Bucket.slot1` — last-refill timestamp in ms (0 = uninitialised → bucket starts full)

One CAS loop on `slot0` per request; `slot1` updated best-effort after CAS success.
The bounded inaccuracy from the non-atomic dual update is at most a few nanoseconds —
negligible and self-correcting for rate limiting.

### Bucket starts full

On the very first request, `slot1 == 0`. The strategy treats this as "one full window
elapsed" → `currentTokens = max`. The bucket starts at capacity; no warm-up needed.

### When to use

- APIs where clients legitimately batch requests (analytics, bulk imports).
- Endpoints where smooth throughput matters more than hard per-second guarantees.
- Any scenario where `FIXED_WINDOW` boundary bursts would be problematic.

---

## Comparison

| | `FIXED_WINDOW` | `TOKEN_BUCKET` |
|---|---|---|
| Algorithm | Aligned counter reset | Continuous token refill |
| Burst handling | Allows 2× limit at boundaries | Absorbs bursts up to bucket capacity |
| Memory per bucket | 1 × `AtomicLong` used | 2 × `AtomicLong` used |
| Clock alignment | Yes (predictable resets) | No (smooth) |
| Typical use case | Simple request quotas | APIs with legitimate burst patterns |
| CAS operations per request | 1 (usually) | 1 (usually) |

Both strategies use the same `Bucket` type. Both are lock-free and allocation-free after
the bucket is first created.

---

## Adding a custom strategy

Implement `RateLimitStrategy` and wrap it in a `LimitStrategy` enum constant:

```java
// 1. Implement the strategy
public final class SlidingWindowStrategy implements RateLimitStrategy {
    @Override
    public boolean check(Bucket bucket, LimitConfig cfg, long[] out) {
        // ... lock-free implementation using bucket.slot0 / slot1
        return allowed;
    }
}

// 2. Add to the enum
public enum LimitStrategy {
    FIXED_WINDOW { ... },
    TOKEN_BUCKET { ... },
    SLIDING_WINDOW {
        @Override
        public RateLimitStrategy create() { return new SlidingWindowStrategy(); }
    };
    public abstract RateLimitStrategy create();
}
```

The new strategy is immediately available to `@Limit(strategy = LimitStrategy.SLIDING_WINDOW)`
and `guard.limit("ip", 100, 1, SECONDS, LimitStrategy.SLIDING_WINDOW)`.

### Strategy contract

```java
public interface RateLimitStrategy {
    /**
     * @param bucket  pre-allocated per-key state (never null)
     * @param cfg     immutable rule config (limit, windowMs)
     * @param out     out[0] = remaining, out[1] = reset epoch-seconds
     * @return true = allowed, false = rejected (429)
     */
    boolean check(Bucket bucket, LimitConfig cfg, long[] out);
}
```

Requirements for custom implementations:
- **Lock-free** — use `AtomicLong.compareAndSet`; no `synchronized` or `ReentrantLock`.
- **Stateless** — all mutable state must live in `Bucket.slot0` / `Bucket.slot1`.
- **No allocation** — `out[]` is the only output channel; do not create objects on the hot path.
- **Thread-safe** — called concurrently from many virtual threads.
