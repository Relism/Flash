package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Internal JSON access shared by the whole extension. Deliberately tree/streaming only —
 * no {@code readValue(bytes, Class)} databinding anywhere in this extension. Request bodies
 * are parsed once into a {@link JsonNode} (no reflection, no property matching against a
 * target class); responses are written directly with {@link JsonGenerator} against the
 * envelope's fixed, known shape (also no reflection).
 *
 * <p>Not wired to {@code flash-ext-jackson} on purpose: the MCP JSON-RPC envelope is internal
 * protocol plumbing, not a user-facing serialization concern, so this extension owns its
 * mapper independently — the same reasoning any protocol-level extension applies to its own JSON needs
 * (see {@code json-smart} there). See {@code docs/jackson-interop.md} for the full rationale
 * and how a future opt-in reuse of a shared {@code ObjectMapper} could work.
 */
final class McpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {}

    static JsonNode parse(byte[] body) throws IOException {
        return MAPPER.readTree(body);
    }

    static JsonGenerator generator(OutputStream out) throws IOException {
        return MAPPER.getFactory().createGenerator(out, JsonEncoding.UTF8);
    }

    /** Builds a small JSON document in one shot; used only for boot-time precompilation. */
    static byte[] build(JsonWriter writer) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        try (JsonGenerator gen = generator(buf)) {
            writer.write(gen);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build MCP JSON fragment", e);
        }
        return buf.toByteArray();
    }

    static String buildString(JsonWriter writer) {
        return new String(build(writer), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    interface JsonWriter {
        void write(JsonGenerator gen) throws IOException;
    }
}
