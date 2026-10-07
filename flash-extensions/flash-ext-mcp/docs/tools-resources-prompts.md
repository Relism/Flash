# Tools, resources and prompts

Classes under `McpConfig.toolsPackage(...)` (subpackages included) that carry `@Tool`, `@Resource`
or `@Prompt` and implement the matching interface are registered. Each is built once at boot through
its constructor, like a handler, so services come as constructor parameters. Each interface has a
`default void onInit()` that runs once after construction.

A package with none of the three fails the boot. A duplicate tool name, resource URI or prompt name
fails the boot. The `tools/list`, `resources/list` and `prompts/list` payloads are built once at boot.

## Tools

```java
@Tool(name = "convert", title = "Convert", description = "Convert a unit",
      args = {
          @ToolArg(name = "value", type = ToolArgType.NUMBER, required = true),
          @ToolArg(name = "to", description = "Target unit", required = true)
      })
final class Convert implements McpTool {
    @Override public ToolResponse call(ToolArguments args) throws Exception {
        return ToolResponse.success(new TextContent(String.valueOf(args.getDouble("value") * 2)));
    }
}
```

`@Tool` has `name` (required), `title`, `description` and `args`. The arguments become the tool's
JSON Schema `inputSchema`: an object with one property per `@ToolArg` (`name`, `type`, `description`)
and a `required` list.

| `ToolArgType` | JSON Schema type |
|---|---|
| `STRING` (default), `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY` | the same, in lowercase |

The schema is not used to validate calls: the server does not check argument types or required
arguments. A tool reads what it needs from `ToolArguments`.

### ToolArguments

| Method | Returns |
|---|---|
| `has(name)` | Whether the argument is present |
| `getString`, `getInt`, `getLong`, `getDouble`, `getBoolean` | The value. Without a default, a missing value is `null` for strings and zero or `false` otherwise. With a default, that default |
| `raw(name)` | The Jackson `JsonNode`, for nested values and arrays |

### ToolResponse

| Factory | Meaning |
|---|---|
| `ToolResponse.success(content...)` | A result. Content blocks are `TextContent(text)` |
| `ToolResponse.error(message)` | A tool failure with `isError: true`, shown to the model |

An exception thrown from `call` becomes `ToolResponse.error("Tool \"name\" failed: <message>")`.

## Resources

```java
@Resource(uri = "config://app", name = "Settings", description = "Application settings",
          mimeType = "application/json")
final class Settings implements McpResource {
    @Override public ResourceContents read() {
        return TextResourceContents.of(uri(), "application/json", "{\"mode\":\"prod\"}");
    }
}
```

`@Resource` has `uri` (required), `name` (default: the uri), `description` and `mimeType` (default
`text/plain`). `uri()` returns the declared URI. Resources are looked up by exact URI; templates are
not supported. The only contents type is `TextResourceContents(uri, mimeType, text)`.

## Prompts

```java
@Prompt(name = "summarize", description = "Summarize text", args = @PromptArg(name = "text", required = true))
final class Summarize implements McpPrompt {
    @Override public PromptMessage render(PromptArguments args) {
        return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
    }
}
```

`@Prompt` has `name` (required), `description` and `args` (`@PromptArg(name, description, required)`;
prompt arguments are strings). `PromptArguments` has `has` and `getString`. A `PromptMessage` has a role
(`USER` or `ASSISTANT`) and one content block; use `withUserRole` or `withAssistantRole`.

## Content types

Only text is supported. `Content` and `ResourceContents` are sealed interfaces with `TextContent` and
`TextResourceContents` as their only implementations.
