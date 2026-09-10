package dev.relism.flash.ext.mcp.authfixtures.authenticatedonly;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.TextContent;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;
import dev.relism.flash.ext.auth.Authenticated;

/** Deliberately misconfigured fixture: bare @Authenticated has no effect on an McpTool — see
 *  McpOidcIntegration#compileToolPolicy. Boot must fail with a clear message, not silently no-op. */
@Tool(name = "pointless", description = "Exists only to prove @Authenticated alone fails boot")
@Authenticated
public class PointlessAuthTool extends McpTool {

    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent("unreachable"));
    }
}
