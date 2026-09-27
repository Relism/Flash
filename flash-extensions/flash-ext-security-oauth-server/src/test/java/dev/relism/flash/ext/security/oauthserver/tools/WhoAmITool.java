package dev.relism.flash.ext.security.oauthserver.tools;

import dev.relism.flash.ext.mcp.McpTool;
import dev.relism.flash.ext.mcp.TextContent;
import dev.relism.flash.ext.mcp.Tool;
import dev.relism.flash.ext.mcp.ToolArguments;
import dev.relism.flash.ext.mcp.ToolResponse;
import dev.relism.flash.ext.security.SecurityIdentity;

@Tool(name = "whoami", description = "The caller's name")
public class WhoAmITool implements McpTool {
    @Override
    public ToolResponse call(ToolArguments args) {
        return ToolResponse.success(new TextContent(SecurityIdentity.current().principal().name()));
    }
}
