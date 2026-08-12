package dev.relism.flash.ext.mcp;

/**
 * Declares one input argument of a {@link Tool}. Used inside {@link Tool#args()} — the whole
 * input JSON Schema is assembled once at scan time from these, never at call time.
 */
public @interface ToolArg {
    String name();
    ToolArgType type() default ToolArgType.STRING;
    String description() default "";
    boolean required() default false;
}
