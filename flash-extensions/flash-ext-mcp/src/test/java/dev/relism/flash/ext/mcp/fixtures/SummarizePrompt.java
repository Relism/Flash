package dev.relism.flash.ext.mcp.fixtures;

import dev.relism.flash.ext.mcp.McpPrompt;
import dev.relism.flash.ext.mcp.Prompt;
import dev.relism.flash.ext.mcp.PromptArg;
import dev.relism.flash.ext.mcp.PromptArguments;
import dev.relism.flash.ext.mcp.PromptMessage;
import dev.relism.flash.ext.mcp.TextContent;

@Prompt(name = "summarize", description = "Summarizes the given text",
        args = @PromptArg(name = "text", required = true))
public class SummarizePrompt extends McpPrompt {

    @Override
    public PromptMessage render(PromptArguments args) {
        return PromptMessage.withUserRole(new TextContent("Summarize: " + args.getString("text")));
    }
}
