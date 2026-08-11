package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Typed accessor over a {@code prompts/get} request's {@code arguments} object.
 * Per the MCP specification, prompt arguments are always strings.
 */
public final class PromptArguments {

    private final JsonNode node;

    PromptArguments(JsonNode node) {
        this.node = node != null ? node : MissingNode.getInstance();
    }

    public boolean has(String name) { return node.has(name); }
    public String getString(String name) { return node.path(name).asText(null); }
    public String getString(String name, String defaultValue) { return node.path(name).asText(defaultValue); }
}
