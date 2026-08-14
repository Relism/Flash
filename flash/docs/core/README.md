# Flash core

The parts of Flash shared by every protocol it speaks — HTTP/1.1 and HTTP/2 alike. Protocol-specific
internals (frames, HPACK, stream state) live in [`../http2/`](../http2/README.md).

- [HTTP/1.1 hardening](HTTP1-HARDENING.md) — message-boundary rules, timeouts and negotiation.
- [Transport](TRANSPORT.md) — listeners, connection ownership, TLS and virtual threads.
- [Message model](MESSAGE-MODEL.md) — shared request/response objects and their lifetime contract.
- [Trailers and streaming](TRAILERS-AND-STREAMING.md) — the public cross-protocol APIs.
- [Byte primitives](BYTES.md) — reusable views, scanning and bounded slice lifetimes.
