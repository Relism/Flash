package dev.relism.flash.ext.mcp.authfixtures.secured;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.TextContent;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;
import dev.relism.flash.ext.security.ScopesAllowed;

@Tool(name = "write_only", description = "Only callable with the write scope")
@ScopesAllowed("write")
public class WriteScopeTool extends McpTool {

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent("written"));
    }
}
