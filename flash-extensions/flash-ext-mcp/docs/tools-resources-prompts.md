# Tools, Resources, Prompts

## One class per feature

Every tool, resource, and prompt is its own class — the same shape as a Flash `Handler`, minus
the HTTP-specific bits:

```java
public interface McpTool {
    ToolResponse call(ToolArguments args) throws Exception;  // hot path
    default void onInit() {}                                 // boot-time work, once
}
```

`McpResource` (`read()`) and `McpPrompt` (`render(PromptArguments)`) follow the exact same
shape. The app builds each class through its constructor, so a tool takes its services there.
There is deliberately no field injection and no method-per-tool bean class — Flash5 handlers are
classes, and MCP features follow that convention.

## Declaring metadata

Metadata (name, description, input schema) lives entirely in the annotation, not in reflected
method signatures — the whole JSON Schema is known at scan time and compiled once:

```java
@Tool(
    name = "get_weather",
    description = "Get current weather for a city",
    args = {
        @ToolArg(name = "city", description = "City name", required = true),
        @ToolArg(name = "days", type = ToolArgType.INTEGER, description = "Forecast horizon")
    }
)
public final class GetWeatherTool implements McpTool {
    @Override
    public ToolResponse call(ToolArguments args) {
        String city = args.getString("city");
        int days = args.getInt("days", 1);
        ...
    }
}
```

`ToolArgType` maps directly to JSON Schema primitive types: `STRING`, `INTEGER`, `NUMBER`,
`BOOLEAN`, `OBJECT`, `ARRAY`. Nested object/array schemas beyond the primitive type keyword are
not modeled in this revision — declare those tools with a looser `OBJECT`/`ARRAY` type and parse
the raw shape via `ToolArguments.raw(name)`.

`ToolArguments`/`PromptArguments` are thin typed accessors over the already-parsed JSON — no
databinding, no reflection, no intermediate DTO:

```java
args.getString("city");
args.getInt("days", 1);
args.getBoolean("metric", true);
args.raw("filters");   // escape hatch: JsonNode for nested/array arguments
```

## Discovery

`McpConfig.toolsPackage("com.example.tools")` scans that package (and subpackages) for concrete
`McpTool`/`McpResource`/`McpPrompt` subclasses carrying `@Tool`/`@Resource`/`@Prompt`. Same
fail-fast contract as `App.scan()`: a missing package, a constructor the app cannot satisfy, or a
class that fails to load aborts startup immediately with a clear message. Duplicate names/URIs also
fail fast at boot.

## Resources and Prompts

```java
@Resource(uri = "config://app-settings", description = "Application settings", mimeType = "application/json")
public final class AppSettingsResource implements McpResource {
    @Override
    public ResourceContents read() {
        return TextResourceContents.of(uri(), "application/json", settingsJson());
    }
}

@Prompt(name = "summarize", args = @PromptArg(name = "text", required = true))
public final class SummarizePrompt implements McpPrompt {
    @Override
    public PromptMessage render(PromptArguments args) {
        return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
    }
}
```

`McpResource.uri()` returns the URI declared on `@Resource`, cached at bind time — no repeated
annotation lookups on the hot path.

## Content types

`Content` and `ResourceContents` are `sealed`, currently permitting only `TextContent` and
`TextResourceContents` respectively. This is a deliberate v1 scope cut, not an oversight — image
content, embedded resources, and blob resources are extension points for a future revision
(extend the `permits` clause and `McpContentWriter`).

## Tool failures vs. protocol errors

A `McpTool.call(...)` that throws is caught by the dispatcher and turned into
`ToolResponse.error(message)` — per the MCP specification this is a normal JSON-RPC *result*
with `isError: true`, not a JSON-RPC error, so the calling model can see and react to it. Prefer
returning `ToolResponse.error(...)` explicitly when you can produce a better message than the
raw exception text.

`McpResource.read()`/`McpPrompt.render(...)` failures, by contrast, surface as JSON-RPC errors
(`-32603 Internal error`) — the specification does not define a soft-failure content convention
for those two.
