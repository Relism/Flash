package dev.relism.flash.ext.mcp.fixtures;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.TextContent;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArg;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;

@Tool(name = "echo", description = "Echoes the given text",
      args = @ToolArg(name = "text", description = "Text to echo", required = true))
public class EchoTool implements McpTool {

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent(args.getString("text")));
    }
}
