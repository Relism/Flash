# Why no `flash-ext-jackson` interop (yet)

## The decision

`flash-ext-mcp` does not depend on, or integrate with, `flash-ext-jackson`. It brings its own
JSON handling (`jackson-databind`/`jackson-core` as a plain library dependency, wrapped by the
internal `McpJson` utility) and never touches `flash-ext-jackson`'s `Json`/`JacksonMiddleware`/
shared `ObjectMapper`, even if the host app has `flash-ext-jackson` installed. This was a
deliberate choice, discussed and made explicitly — not an oversight — and is written down here
so it isn't accidentally "fixed" later without re-litigating the trade-off.

## Why

`flash-ext-jackson`'s `Json` class is built around full databinding:
`mapper.readValue(bytes, SomeDto.class)` / `mapper.writeValueAsBytes(obj)` — reflection-driven
property matching in both directions. The MCP JSON-RPC envelope has a **fixed, known shape**
(`{jsonrpc, id, method, params}` in, `{jsonrpc, id, result|error}` out) defined by a spec, not by
application DTOs. Given that, hand-writing it with `JsonGenerator` directly is both simpler and
strictly cheaper than round-tripping through databinding: no property-name matching, no
reflection, no intermediate POJO graph for the parts of the response this extension controls
(the envelope itself, `tools/list`/`resources/list`/`prompts/list` — precompiled once at boot,
see `tools-resources-prompts.md` — and the fixed `TextContent`/`TextResourceContents`/
`PromptMessage` shapes). `ToolArguments`/`PromptArguments` read the incoming `arguments` object
as a `JsonNode` tree, not as a databound class, for the same reason — a JSON-RPC tool call's
arguments aren't a DTO with getters/setters, they're a dynamic, per-tool-defined bag of values.

This mirrors how `flash-ext-security-oidc` handles its own JSON needs (Nimbus's parser for
token-endpoint responses) independently of `flash-ext-jackson` — extensions with protocol-level
JSON needs that are shaped by a spec, not by user code, own that JSON handling themselves rather
than routing it through the app's general-purpose JSON extension.

## What this means practically

- Installing `flash-ext-mcp` never requires installing `flash-ext-jackson`. A pure MCP server
  with no other JSON REST routes has zero unrelated dependencies to configure.
- If the host app *does* have `flash-ext-jackson` installed for its own REST routes, that
  `ObjectMapper`'s configuration (custom modules, date formatting, naming strategy, etc.) is
  **not** consulted by `flash-ext-mcp` — the two JSON paths are entirely independent today.

## What a future opt-in reuse could look like

Nothing here rules out a later, additive convenience layer: `McpModule` could look up
`services.find(Json.class)` (added by jackson's `JsonModule`) and, if present, use
that shared codec as the backing for an escape hatch such as `ToolArguments.as(Class<T>)` or
for a tool that wants to `ToolResponse.success(someRecord)` and have it serialized with the
app's own conventions — falling back to a locally-constructed default `ObjectMapper` when
`flash-ext-jackson` isn't installed. That would be purely additive on top of the
`JsonGenerator`-based envelope/content writing described above, not a replacement for it — the
fixed-shape protocol plumbing has no reason to ever go through databinding, regardless of what
convenience layer gets added around it.
