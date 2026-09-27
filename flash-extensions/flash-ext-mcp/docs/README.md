# flash-ext-mcp

`flash-ext-mcp` turns a Flash5 app into an [MCP](https://modelcontextprotocol.io) (Model Context
Protocol) server: JSON-RPC 2.0 over the Streamable HTTP transport, tools/resources/prompts
declared as plain classes and discovered at boot, optional OAuth2 protection built on
`flash-ext-security-core`.

## Quick Start

```java
App.create()
    .install(new McpModule(McpConfig.builder("my-mcp-server")
        .toolsPackage("com.example.tools")
        .build()));
```

```java
@Tool(name = "get_weather", description = "Get current weather for a city",
      args = @ToolArg(name = "city", description = "City name", required = true))
public final class GetWeatherTool implements McpTool {

    private final WeatherService weatherService;

    public GetWeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent(weatherService.fetch(args.getString("city"))));
    }
}
```

## Operating Model

- **One class per tool/resource/prompt**, like a handler: services through the constructor, an
  optional `onInit()` for boot-time work, one hot-path method (`call`/`read`/`render`). No field
  injection, no reflection on the hot path.
- **Boot-time precompilation** — `tools/list`/`resources/list`/`prompts/list` JSON payloads
  (including JSON Schema) are built once at boot and spliced verbatim into responses. See
  `tools-resources-prompts.md`.
- **Transport**: Streamable HTTP, `POST`-only, stateless in this revision — see `transport.md`
  for exactly what that means and why.
- **Security**: authenticated by `flash-ext-security-core`, an OAuth2 protected resource when OIDC is installed — see `security.md`.
- **JSON**: this module owns its JSON handling independently of `flash-ext-jackson` — see
  `jackson-interop.md` for why, and how a future opt-in reuse could work.

## Documents

- [`tools-resources-prompts.md`](tools-resources-prompts.md) — defining tools, resources, prompts
- [`transport.md`](transport.md) — Streamable HTTP scope, session/SSE limitations, Origin validation
- [`security.md`](security.md) — `McpSecurity` policy, OAuth2 resolution, RFC 9728 / RFC 8707
- [`jackson-interop.md`](jackson-interop.md) — why this extension does not depend on `flash-ext-jackson`
