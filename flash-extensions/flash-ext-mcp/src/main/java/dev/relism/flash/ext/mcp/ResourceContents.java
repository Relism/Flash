package dev.relism.flash.ext.mcp;

/**
 * MCP resource contents. {@code sealed} to the variants this extension currently writes on
 * the wire — extend the permits clause (and {@link McpContentWriter}) to add
 * {@code BlobResourceContents} in a future revision.
 */
public sealed interface ResourceContents permits TextResourceContents {}
