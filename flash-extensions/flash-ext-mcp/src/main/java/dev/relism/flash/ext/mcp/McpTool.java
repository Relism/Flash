package dev.relism.flash.ext.mcp;

/**
 * One MCP tool: metadata on {@link Tool @Tool}, services through the constructor, the hot path in
 * {@link #call(ToolArguments)}. Found under {@link McpConfig#toolsPackage(String)} and built once
 * at boot, like a handler.
 *
 * <pre>{@code
 * @Tool(name = "get_weather", description = "Get current weather for a city",
 *       args = @ToolArg(name = "city", required = true))
 * public final class GetWeatherTool implements McpTool {
 *     private final WeatherService weather;
 *
 *     public GetWeatherTool(WeatherService weather) { this.weather = weather; }
 *
 *     @Override public ToolResponse call(ToolArguments args) {
 *         return ToolResponse.success(new TextContent(weather.fetch(args.getString("city"))));
 *     }
 * }
 * }</pre>
 */
public interface McpTool {

    ToolResponse call(ToolArguments args) throws Exception;

    /** Runs once at boot, after construction: work that belongs off the hot path. */
    default void onInit() {}
}
