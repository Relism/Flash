# flash-ext-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) server over Streamable HTTP. Tools,
resources and prompts are plain classes found by package scan and built with their services. Every
call is authenticated through `flash-ext-security-core`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-mcp</artifactId>
</dependency>
```

```java
app.install(
        new SecurityModule().origin("https://app.example"),
        new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)),
        new McpModule(McpConfig.builder("orders")
                .toolsPackage("com.acme.mcp")
                .allowedOrigins("https://app.example")
                .build()));

@Tool(name = "get_order", description = "Fetch an order",
      args = @ToolArg(name = "id", type = ToolArgType.INTEGER, required = true))
final class GetOrder implements McpTool {
    private final Orders orders;
    GetOrder(Orders orders) { this.orders = orders; }

    @Override public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent(orders.describe(args.getLong("id"))));
    }
}
```

| Page | Content |
|---|---|
| [docs/tools-resources-prompts.md](docs/tools-resources-prompts.md) | `@Tool`, `@Resource`, `@Prompt` and what they return |
| [docs/transport.md](docs/transport.md) | The endpoint, supported methods, error behavior |
| [docs/security.md](docs/security.md) | Authentication, audience, origins, per-tool authorization |
| [docs/configuration.md](docs/configuration.md) | `McpConfig` |
