package dev.relism.flash.ext.mcp;

/**
 * MCP tool/prompt content block. {@code sealed} to the variants this extension currently
 * writes on the wire — extend the permits clause (and {@link McpContentWriter}) to add
 * {@code ImageContent}, {@code EmbeddedResource}, etc. in a future revision.
 */
public sealed interface Content permits TextContent {}
