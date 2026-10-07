# Transport

The server implements the MCP Streamable HTTP transport in its stateless form: one endpoint,
`POST <rootPath>` (default `/mcp`), carrying one JSON-RPC 2.0 message per request. There is no
session, no `GET` event stream and no server-initiated message, so any instance can answer any
request.

Protocol revision reported at `initialize`: `2025-11-25`.

## Methods

| Method | Behavior |
|---|---|
| `initialize` | Returns the protocol version, `serverInfo` (name and version from the config), the capabilities, and the optional `instructions`. Capabilities `tools`, `resources` and `prompts` appear only when at least one of that kind exists, each with `listChanged: false` |
| `ping` | Empty result |
| `tools/list`, `resources/list`, `prompts/list` | The catalogs built at boot |
| `tools/call` | Params `name` and optional `arguments` |
| `resources/read` | Param `uri` |
| `prompts/get` | Params `name` and optional `arguments` |
| `notifications/initialized`, `notifications/cancelled` | Accepted, answered `202` with no body |

Any other notification (a message with no `id`) is answered `202`. Any other method with an `id` is
the JSON-RPC error `-32601`.

## Response codes

| Situation | HTTP | Body |
|---|---|---|
| Result | 200 | `{"jsonrpc":"2.0","id":...,"result":...}` |
| Body is not valid JSON | 400 | Error `-32700` |
| Body is not a JSON object | 400 | Error `-32600` |
| Request without `method` | 400 | Error `-32600` (a notification: `202`) |
| Missing `name` or `uri`, or an unknown tool, resource or prompt | 200 | Error `-32602` |
| Unknown method | 200 | Error `-32601` |
| An exception while handling a method | 200 | Error `-32603`, `Internal error: <message>` |
| A tool fails or throws | 200 | A result with `isError: true` |
| A tool is denied by `@RolesAllowed` or `@ScopesAllowed` | 200 | A result with `isError: true`: `Tool "x" denied: missing role` or `missing scope` |

JSON-RPC errors are returned with HTTP 200 because the HTTP request succeeded. Authentication
failures happen before dispatch and are ordinary `401` and `403` responses. See
[security.md](security.md).

An `id` can be a string or a number and is echoed back.
