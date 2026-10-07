# McpConfig

```java
McpConfig config = McpConfig.builder("orders")
        .version("2.1.0")
        .instructions("Use get_order for lookups.")
        .rootPath("/mcp")
        .toolsPackage("com.acme.mcp")
        .security(McpSecurity.REQUIRED)
        .allowedOrigins("https://app.example")
        .scopesSupported("tools:call")
        .build();
```

| Method | Default | Meaning |
|---|---|---|
| `builder(name)` | required, not blank | The server name in `serverInfo` |
| `version(String)` | `1.0.0` | Version in `serverInfo` |
| `instructions(String)` | none | Text sent to the client at `initialize` |
| `rootPath(String)` | `/mcp` | The endpoint path. A leading `/` is added |
| `toolsPackage(String)` | required | Package scanned for tools, resources and prompts. `build()` fails without it |
| `security(McpSecurity)` | `REQUIRED` | `REQUIRED` or `NONE` |
| `requireTokenAudience(boolean)` | `true` | Whether a token must name the endpoint as its audience |
| `allowedOrigins(String...)` | none, check disabled | Allowed `Origin` values |
| `scopesSupported(String...)` | none | Published as `scopes_supported` in the resource metadata |
| `mechanisms(AuthenticationMechanism...)` | the whole chain | Restrict which mechanisms authenticate the endpoint |
| `middleware(Middleware...)` | none | Extra middleware on the route |

`McpModule` registers the endpoint as a `POST` route at `rootPath`, and, when security is required,
`GET /.well-known/oauth-protected-resource<rootPath>`.
