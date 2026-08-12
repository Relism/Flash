# Transport

`flash-ext-mcp` implements the **Streamable HTTP** transport from the MCP specification
(revision `2025-11-25`). `stdio` is out of scope — Flash5 is an HTTP framework, and a
subprocess-stdio transport doesn't fit its model.

## What this revision implements

- A single `POST {rootPath}` endpoint (default `/mcp`) accepting one JSON-RPC 2.0 message per
  request and responding with a plain JSON object — the "standard JSON object response" mode the
  specification allows as an alternative to opening a Server-Sent Events stream per request.
- `Origin` header validation (DNS-rebinding protection), configurable via
  `McpConfig.allowedOrigins(...)`.
- Full JSON-RPC lifecycle: `initialize`, `notifications/initialized` (and any other
  `notifications/*`/id-less message — answered with a bare `202 Accepted`, no body, per
  JSON-RPC's notification semantics), `ping`, `tools/list`, `tools/call`, `resources/list`,
  `resources/read`, `prompts/list`, `prompts/get`.

## What this revision deliberately does not implement

- **No `Mcp-Session-Id` / session state.** The specification says a server "MAY assign a session
  ID at initialization time" — it is optional, not mandatory. This server is stateless: every
  `POST` is handled independently, with no server-side session store. `initialize` does not need
  to precede other calls for the server to function (there's no session to be "not initialized"
  yet), which is a looser contract than a session-aware server would enforce — acceptable for a
  static, boot-time-defined tool/resource/prompt catalog.
- **No Server-Sent Events stream.** `GET {rootPath}` (used by session-aware servers to open a
  standing SSE stream for server-initiated pushes) is not registered — MCP clients that only
  speak the request/response half of Streamable HTTP work unaffected; clients that require a
  standing SSE connection are not supported by this revision.

Both are real, intentional scope cuts for a first version — not just to keep the surface area
small: a static, precompiled tool catalog (see `tools-resources-prompts.md`) has no
`listChanged` events to push and no long-running server-initiated messages to stream, so the
stateful half of the transport buys little for the common case this extension targets. Sessions
and SSE are natural extension points if a future revision needs server push (e.g. dynamic tool
registration, elicitation, or sampling requests initiated by the server).

## Why `POST`, not the new `QUERY` HTTP method

Flash5's core recently gained `HttpMethod.QUERY` (safe, idempotent, carries a body — a good
semantic fit for JSON-RPC-over-HTTP in general). It is **not** used here: the MCP Streamable
HTTP specification mandates `POST` for the client-to-server message path. Real MCP clients send
`POST`; using `QUERY` instead would break interoperability with every existing client for a
semantic nicety this extension doesn't need standalone.
