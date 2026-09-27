package dev.relism.flash.ext.mcp;

/**
 * One MCP prompt template: metadata on {@link Prompt @Prompt}, services through the constructor,
 * the hot path in {@link #render(PromptArguments)}.
 *
 * <pre>{@code
 * @Prompt(name = "summarize", args = @PromptArg(name = "text", required = true))
 * public final class SummarizePrompt implements McpPrompt {
 *     @Override public PromptMessage render(PromptArguments args) {
 *         return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
 *     }
 * }
 * }</pre>
 */
public interface McpPrompt {

    PromptMessage render(PromptArguments args) throws Exception;

    /** Runs once at boot, after construction: work that belongs off the hot path. */
    default void onInit() {}
}
