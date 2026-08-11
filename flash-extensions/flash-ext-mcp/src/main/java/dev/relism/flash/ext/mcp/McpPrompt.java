package dev.relism.flash.ext.mcp;

import dev.relism.flash.extension.FlashContext;

import java.util.Optional;

/**
 * Base class for a single MCP prompt template — one class per prompt, mirroring {@link McpTool}.
 * Declare metadata with {@link Prompt @Prompt}, cache services in {@link #onInit()}, implement
 * {@link #render(PromptArguments)} for the hot path.
 *
 * <pre>{@code
 * @Prompt(name = "summarize", args = @PromptArg(name = "text", required = true))
 * public class SummarizePrompt extends McpPrompt {
 *     @Override public PromptMessage render(PromptArguments args) {
 *         return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
 *     }
 * }
 * }</pre>
 */
public abstract class McpPrompt {

    private FlashContext ctx;

    /**
     * Called once by the framework after instantiation, before the first {@code prompts/get}.
     * <b>Infrastructure method</b> — do not call from user code.
     */
    public final void bind(FlashContext ctx) {
        this.ctx = ctx;
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

    private void checkBound() {
        if (ctx == null)
            throw new IllegalStateException(
                    getClass().getSimpleName() + " has not been bound to a FlashContext — " +
                    "register via McpConfig.toolsPackage(), not by instantiating directly");
    }

    /** Invoked on every matching {@code prompts/get} request (hot path). */
    public abstract PromptMessage render(PromptArguments args) throws Exception;
}
