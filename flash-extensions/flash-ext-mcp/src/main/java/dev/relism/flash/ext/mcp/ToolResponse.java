package dev.relism.flash.ext.mcp;

import java.util.List;

/** Result of a {@link McpTool#call(ToolArguments)} invocation. */
public final class ToolResponse {

    private final List<Content> content;
    private final boolean isError;

    private ToolResponse(List<Content> content, boolean isError) {
        this.content = content;
        this.isError = isError;
    }

    /** Successful tool result carrying one or more content blocks. */
    public static ToolResponse success(Content... content) {
        return new ToolResponse(List.of(content), false);
    }

    /**
     * Tool-level failure — per the MCP specification this is still a normal JSON-RPC
     * <em>result</em> (not a JSON-RPC error) with {@code isError: true}, so the model can see
     * and react to it.
     */
    public static ToolResponse error(String message) {
        return new ToolResponse(List.of(new TextContent(message)), true);
    }

    List<Content> content() { return content; }
    boolean isError() { return isError; }
}
