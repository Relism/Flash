package dev.relism.flash.ext.mcp;

/**
 * Declares one argument of a {@link Prompt}. Per the MCP specification, prompt arguments are
 * always strings. Used inside {@link Prompt#args()}.
 */
public @interface PromptArg {
    String name();
    String description() default "";
    boolean required() default false;
}
