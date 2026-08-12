package dev.relism.flash.ext.mcp.authfixtures.secured;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.TextContent;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;

/** No role/scope annotation — any authenticated caller, confirms unrelated tools are unaffected. */
@Tool(name = "open", description = "Callable by anyone already authenticated")
public class OpenTool extends McpTool {

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent("open"));
    }
}
