package dev.relism.flash.ext.mcp.fixtures;

import dev.relism.flash.ext.mcp.McpResource;
import dev.relism.flash.ext.mcp.Resource;
import dev.relism.flash.ext.mcp.ResourceContents;
import dev.relism.flash.ext.mcp.TextResourceContents;

@Resource(uri = "greeting://hello", description = "A greeting", mimeType = "text/plain")
public class GreetingResource extends McpResource {

    @Override
    public ResourceContents read() {
        return TextResourceContents.of(uri(), "text/plain", "hello world");
    }
}
