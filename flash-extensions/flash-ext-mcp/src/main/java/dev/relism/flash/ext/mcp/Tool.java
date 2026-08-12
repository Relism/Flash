package dev.relism.flash.ext.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link McpTool} subclass as an MCP tool and declares its metadata, discovered by
 * {@link McpConfig#toolsPackage(String)}.
 *
 * <pre>{@code
 * @Tool(
 *     name = "get_weather",
 *     description = "Get current weather for a city",
 *     args = @ToolArg(name = "city", description = "City name", required = true)
 * )
 * public class GetWeatherTool extends McpTool {
 *     @Override
 *     public ToolResponse call(ToolArguments args) {
 *         return ToolResponse.success(new TextContent(weatherService.fetch(args.getString("city"))));
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tool {
    /** Unique tool name (used by clients in {@code tools/call}). */
    String name();

    /** Human/model-readable description of what the tool does. */
    String description() default "";

    /** Optional display title, distinct from {@link #name()}. */
    String title() default "";

    /** Input arguments — assembled into the tool's JSON Schema {@code inputSchema} once at boot. */
    ToolArg[] args() default {};
}
