package dev.relism.flash.ext.mcp.fixtures;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;

@Tool(name = "boom", description = "Always fails")
public class FailingTool implements McpTool {

    @Override
    public ToolResponse call(ToolArguments args) {
        throw new IllegalStateException("kaboom");
    }
}
