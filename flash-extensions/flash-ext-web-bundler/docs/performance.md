# Performance Notes

Current v1 optimizations:

- startup preloading of static assets
- precomputed ETags and MIME lookup
- compressed payload selection based on `Accept-Encoding`
- lockfile-hash install gate to avoid redundant installs

Future performance work:

- streaming/sliced file serving for large assets
- benchmark suite with p50/p95/p99 latency and allocation profiling
