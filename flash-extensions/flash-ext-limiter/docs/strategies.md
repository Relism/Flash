# Strategies

All three are lock-free: they use compare-and-set on two `long` slots in the key's `Bucket` and
allocate nothing per request.

## FIXED_WINDOW

A counter that resets at clock-aligned window boundaries (a 60 second window runs 10:00:00 to
10:00:59). Smallest state. A client can use up to twice the limit by sending a burst at the end of
one window and another at the start of the next.

`X-RateLimit-Reset` is the end of the current window.

## SLIDING_WINDOW

Estimates the rate as `previousCount * (1 - elapsed / window) + currentCount`, in integer
arithmetic. It removes the boundary burst of the fixed window while keeping constant memory. It is
an approximation: at window boundaries the true rate can exceed the limit slightly, and a request
that races a window change may see a slightly stale previous count.

`X-RateLimit-Reset` is the end of the current window.

## TOKEN_BUCKET

A bucket of `requests` tokens that refills continuously at `requests / window`. Each request takes
one token. A new key starts with a full bucket, so an idle client can burst up to `requests` and is
then held to the refill rate.

When a request is refused, `X-RateLimit-Reset` is when the next token arrives. After an allowed
request it is the current second. Tokens are stored in thousandths to avoid floating point.
