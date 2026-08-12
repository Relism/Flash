# flash-ext-mcp

`flash-ext-mcp` turns a Flash5 app into an [MCP](https://modelcontextprotocol.io) (Model Context
Protocol) server: JSON-RPC 2.0 over the Streamable HTTP transport, tools/resources/prompts
declared as plain classes and discovered at boot, optional OAuth2 protection built on
`flash-ext-oidc`.

## Quick Start

```java
FlashApp.create(8080)
    .install(new McpExtension(McpConfig.builder("my-mcp-server")
        .toolsPackage("com.example.tools")
        .build()))
    .start();
```

```java
@Tool(name = "get_weather", description = "Get current weather for a city",
      args = @ToolArg(name = "city", description = "City name", required = true))
public class GetWeatherTool extends McpTool {

    private WeatherService weatherService;

    @Override
    protected void onInit() {
        weatherService = require(WeatherService.class);
    }

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent(weatherService.fetch(args.getString("city"))));
    }
}
```

## Operating Model

- **One class per tool/resource/prompt** — mirrors `RequestHandler`: a no-arg constructor,
  `onInit()` to cache services from `FlashContext`, one hot-path method
  (`call`/`read`/`render`). No CDI, no field injection, no reflection on the hot path.
- **Boot-time precompilation** — `tools/list`/`resources/list`/`prompts/list` JSON payloads
  (including JSON Schema) are built once at boot and spliced verbatim into responses. See
  `tools-resources-prompts.md`.
- **Transport**: Streamable HTTP, `POST`-only, stateless in this revision — see `transport.md`
  for exactly what that means and why.
- **Security**: optional, policy-driven OAuth2 via `flash-ext-oidc` — see `security.md`.
- **JSON**: this extension owns its JSON handling independently of `flash-ext-jackson` — see
  `jackson-interop.md` for why, and how a future opt-in reuse could work.

## Documents

- [`tools-resources-prompts.md`](tools-resources-prompts.md) — defining tools, resources, prompts
- [`transport.md`](transport.md) — Streamable HTTP scope, session/SSE limitations, Origin validation
- [`security.md`](security.md) — `McpSecurity` policy, OAuth2 resolution, RFC 9728 / RFC 8707
- [`keycloak.md`](keycloak.md) — Keycloak-specific setup cookbook: Dynamic Client Registration,
  the RFC 8707 audience mapper gotcha, and how to verify/debug it
- [`jackson-interop.md`](jackson-interop.md) — why this extension does not depend on `flash-ext-jackson`
