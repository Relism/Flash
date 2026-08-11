package dev.relism.flash.ext.mcp;

import dev.relism.flash.extension.FlashContext;

import java.util.Optional;

/**
 * Base class for a single MCP tool — one class per tool, mirroring
 * {@link dev.relism.flash.models.RequestHandler}: declare metadata with {@link Tool @Tool},
 * cache services in {@link #onInit()}, implement {@link #call(ToolArguments)} for the hot path.
 *
 * <p>Discovered via {@link McpConfig#toolsPackage(String)} — instantiated with its public
 * no-arg constructor and bound once at boot, before the first {@code tools/call} request.
 *
 * <pre>{@code
 * @Tool(name = "get_weather", description = "Get current weather for a city",
 *       args = @ToolArg(name = "city", required = true))
 * public class GetWeatherTool extends McpTool {
 *     private WeatherService weatherService;
 *
 *     @Override protected void onInit() {
 *         weatherService = require(WeatherService.class);
 *     }
 *
 *     @Override public ToolResponse call(ToolArguments args) {
 *         return ToolResponse.success(new TextContent(weatherService.fetch(args.getString("city"))));
 *     }
 * }
 * }</pre>
 */
public abstract class McpTool {

    private FlashContext ctx;

    /**
     * Called once by the framework after instantiation, before the first {@code tools/call}.
     * <b>Infrastructure method</b> — do not call from user code.
     */
    public final void bind(FlashContext ctx) {
        this.ctx = ctx;
        onInit();
    }

    /** Override to cache services at boot time. See {@link #require}/{@link #find}. */
    protected void onInit() {}

    protected <T> T require(Class<T> type) {
        checkBound();
        return ctx.require(type);
    }

    protected <T> Optional<T> find(Class<T> type) {
        checkBound();
        return ctx.find(type);
    }

    protected <T> Optional<T> optional(Class<T> type) {
        checkBound();
        return ctx.optional(type);
    }

    private void checkBound() {
        if (ctx == null)
            throw new IllegalStateException(
                    getClass().getSimpleName() + " has not been bound to a FlashContext — " +
                    "register via McpConfig.toolsPackage(), not by instantiating directly");
    }

    /**
     * Invoked on every matching {@code tools/call} request (hot path). {@code args} is a thin
     * accessor over the already-parsed JSON arguments — no databinding.
     */
    public abstract ToolResponse call(ToolArguments args) throws Exception;
}
