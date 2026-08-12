package dev.relism.flash.ext.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link McpPrompt} subclass as an MCP prompt template and declares its metadata,
 * discovered by {@link McpConfig#toolsPackage(String)}.
 *
 * <pre>{@code
 * @Prompt(name = "summarize", args = @PromptArg(name = "text", required = true))
 * public class SummarizePrompt extends McpPrompt {
 *     @Override
 *     public PromptMessage render(PromptArguments args) {
 *         return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Prompt {
    /** Unique prompt name (used by clients in {@code prompts/get}). */
    String name();

    String description() default "";

    /** Arguments accepted by the prompt template — always strings per the MCP specification. */
    PromptArg[] args() default {};
}
