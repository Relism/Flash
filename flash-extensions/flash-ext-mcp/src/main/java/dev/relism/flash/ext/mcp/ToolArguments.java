package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Typed accessor over a {@code tools/call} request's {@code arguments} object.
 *
 * <p>Wraps the already-parsed {@link JsonNode} directly — no POJO databinding, no reflection,
 * no intermediate copy. Same spirit as {@code QueryParams}/{@code PathParams} in Flash core:
 * a thin typed view over data that already exists in memory.
 *
 * <pre>{@code
 * public ToolResponse call(ToolArguments args) {
 *     String city = args.getString("city");
 *     int days    = args.getInt("days", 1);
 *     ...
 * }
 * }</pre>
 */
public final class ToolArguments {

    private final JsonNode node;

    ToolArguments(JsonNode node) {
        this.node = node != null ? node : MissingNode.getInstance();
    }

    public boolean has(String name) { return node.has(name); }

    public String getString(String name) { return node.path(name).asText(null); }
    public String getString(String name, String defaultValue) { return node.path(name).asText(defaultValue); }

    public int getInt(String name) { return node.path(name).asInt(); }
    public int getInt(String name, int defaultValue) { return node.path(name).asInt(defaultValue); }

    public long getLong(String name) { return node.path(name).asLong(); }
    public long getLong(String name, long defaultValue) { return node.path(name).asLong(defaultValue); }

    public double getDouble(String name) { return node.path(name).asDouble(); }
    public double getDouble(String name, double defaultValue) { return node.path(name).asDouble(defaultValue); }

    public boolean getBoolean(String name) { return node.path(name).asBoolean(); }
    public boolean getBoolean(String name, boolean defaultValue) { return node.path(name).asBoolean(defaultValue); }

    /** Escape hatch for nested/array arguments not covered by the typed accessors above. */
    public JsonNode raw(String name) { return node.path(name); }
}
