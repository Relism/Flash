package dev.relism.flash.ext.mcp;

/** JSON Schema primitive types available for {@link ToolArg#type()}. */
public enum ToolArgType {
    STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY;

    /** JSON Schema {@code "type"} keyword value. */
    String jsonSchemaType() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
