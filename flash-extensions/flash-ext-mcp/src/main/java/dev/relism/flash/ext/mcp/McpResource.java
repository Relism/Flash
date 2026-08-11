package dev.relism.flash.ext.mcp;

import dev.relism.flash.extension.FlashContext;

import java.util.Optional;

/**
 * Base class for a single MCP resource — one class per resource, mirroring {@link McpTool}.
 * Declare metadata with {@link Resource @Resource}, cache services in {@link #onInit()},
 * implement {@link #read()} for the hot path.
 *
 * <pre>{@code
 * @Resource(uri = "config://app-settings", description = "Application settings", mimeType = "application/json")
 * public class AppSettingsResource extends McpResource {
 *     @Override public ResourceContents read() {
 *         return TextResourceContents.of(uri(), "application/json", settingsJson());
 *     }
 * }
 * }</pre>
 */
public abstract class McpResource {

    private FlashContext ctx;
    private String uri;

    /**
     * Called once by the framework after instantiation, before the first {@code resources/read}.
     * <b>Infrastructure method</b> — do not call from user code.
     */
    public final void bind(FlashContext ctx) {
        this.ctx = ctx;
        Resource ann = getClass().getAnnotation(Resource.class);
        this.uri = ann != null ? ann.uri() : null;
        onInit();
    }

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

    /** URI declared via {@link Resource @Resource}, cached at bind time. */
    protected final String uri() { return uri; }

    private void checkBound() {
        if (ctx == null)
            throw new IllegalStateException(
                    getClass().getSimpleName() + " has not been bound to a FlashContext — " +
                    "register via McpConfig.toolsPackage(), not by instantiating directly");
    }

    /** Invoked on every matching {@code resources/read} request (hot path). */
    public abstract ResourceContents read() throws Exception;
}
