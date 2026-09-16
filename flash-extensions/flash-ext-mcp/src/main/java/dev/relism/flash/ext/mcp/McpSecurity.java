package dev.relism.flash.ext.mcp;

/** Whether the MCP endpoint requires an authenticated caller. */
public enum McpSecurity {

    /** The default: every call is authenticated by {@code flash-ext-security-core}, which must be installed. */
    REQUIRED,

    /** A public endpoint. Tools declaring security annotations fail the boot. */
    NONE
}
