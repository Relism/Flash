package dev.relism.flash.ext.mcp;

/** A single message returned by a {@link McpPrompt}. */
public record PromptMessage(Role role, Content content) {

    public enum Role { USER, ASSISTANT }

    public static PromptMessage withUserRole(Content content) {
        return new PromptMessage(Role.USER, content);
    }

    public static PromptMessage withAssistantRole(Content content) {
        return new PromptMessage(Role.ASSISTANT, content);
    }
}
