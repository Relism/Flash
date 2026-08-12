package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Direct {@link JsonGenerator} writers for the fixed, known shapes of {@link Content},
 * {@link ResourceContents} and {@link PromptMessage} — no databinding, one {@code switch}
 * per call, matching the fixed wire shape defined by the MCP specification.
 */
final class McpContentWriter {

    private McpContentWriter() {}

    static void writeContentArray(JsonGenerator gen, List<Content> items) throws IOException {
        gen.writeStartArray();
        for (Content c : items) writeContent(gen, c);
        gen.writeEndArray();
    }

    static void writeContent(JsonGenerator gen, Content content) throws IOException {
        if (content instanceof TextContent tc) {
            gen.writeStartObject();
            gen.writeStringField("type", "text");
            gen.writeStringField("text", tc.text());
            gen.writeEndObject();
            return;
        }
        throw new IllegalStateException("Unhandled Content variant: " + content.getClass());
    }

    static void writeResourceContents(JsonGenerator gen, ResourceContents contents) throws IOException {
        if (contents instanceof TextResourceContents trc) {
            gen.writeStartObject();
            gen.writeStringField("uri", trc.uri());
            gen.writeStringField("mimeType", trc.mimeType());
            gen.writeStringField("text", trc.text());
            gen.writeEndObject();
            return;
        }
        throw new IllegalStateException("Unhandled ResourceContents variant: " + contents.getClass());
    }

    static void writePromptMessage(JsonGenerator gen, PromptMessage message) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("role", message.role().name().toLowerCase(Locale.ROOT));
        gen.writeFieldName("content");
        writeContent(gen, message.content());
        gen.writeEndObject();
    }
}
