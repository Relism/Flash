package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.io.IOException;

/**
 * JSON-RPC 2.0 dispatcher for the MCP Streamable HTTP endpoint — one instance per
 * {@link McpExtension}, built once at boot from a resolved {@link McpRegistry}.
 *
 * <p>Per the MCP specification, a {@code tools/call} failure is a normal JSON-RPC
 * <em>result</em> with {@code isError: true} (see {@link ToolResponse#error}), not a JSON-RPC
 * error — the model needs to see it. Everything else that goes wrong (bad params, unknown
 * tool/resource/prompt name, resource/prompt handler exceptions) is a JSON-RPC error object,
 * always returned with HTTP 200: the HTTP request itself succeeded, only the RPC did not. Only
 * malformed HTTP-level input (unparsable JSON, not a JSON object) gets HTTP 400. A
 * {@code @RolesAllowed}/{@code @ScopesAllowed} denial (see {@link McpAuthPolicy}) is the same
 * category — {@code isError: true}, tool never invoked — not a transport-level rejection; the
 * route-wide 401/403 for "not authenticated at all" already happened earlier, in the {@code
 * OidcMiddleware}/audience-guard middleware chain, before this dispatcher ever runs.
 */
final class McpDispatcher {

    /** Protocol revision this dispatcher implements. */
    static final String PROTOCOL_VERSION = "2025-11-25";

    private final McpRegistry registry;
    private final String serverName;
    private final String serverVersion;
    private final String instructions;

    McpDispatcher(McpRegistry registry, String serverName, String serverVersion, String instructions) {
        this.registry = registry;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
    }

    void handle(Request req, Response res) {
        byte[] body = req.body().bytes();
        JsonNode root;
        try {
            root = McpJson.parse(body);
        } catch (IOException e) {
            writeError(res, 400, null, JsonRpcErrorCode.PARSE_ERROR, "Parse error: " + e.getMessage());
            return;
        }
        if (root == null || !root.isObject()) {
            writeError(res, 400, null, JsonRpcErrorCode.INVALID_REQUEST, "Request must be a JSON object");
            return;
        }

        JsonNode idNode = root.get("id");
        boolean isNotification = idNode == null;
        String method = root.path("method").asText(null);
        JsonNode params = root.path("params");

        if (method == null || method.isBlank()) {
            if (isNotification) { res.status(202); return; }
            writeError(res, 400, idNode, JsonRpcErrorCode.INVALID_REQUEST, "Missing \"method\"");
            return;
        }

        try {
            switch (method) {
                case "initialize" -> handleInitialize(res, idNode);
                case "notifications/initialized", "notifications/cancelled" -> res.status(202);
                case "ping" -> handlePing(res, idNode);
                case "tools/list" -> handleToolsList(res, idNode);
                case "tools/call" -> handleToolsCall(res, idNode, params);
                case "resources/list" -> handleResourcesList(res, idNode);
                case "resources/read" -> handleResourcesRead(res, idNode, params);
                case "prompts/list" -> handlePromptsList(res, idNode);
                case "prompts/get" -> handlePromptsGet(res, idNode, params);
                default -> {
                    if (isNotification) { res.status(202); return; }
                    throw McpProtocolException.methodNotFound(method);
                }
            }
        } catch (McpProtocolException e) {
            writeError(res, 200, idNode, e.code, e.getMessage());
        } catch (Exception e) {
            writeError(res, 200, idNode, JsonRpcErrorCode.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    // ── Method handlers ──────────────────────────────────────────────────────

    private void handleInitialize(Response res, JsonNode id) {
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeStringField("protocolVersion", PROTOCOL_VERSION);
            gen.writeObjectFieldStart("capabilities");
            if (registry.hasTools())     writeEmptyCapability(gen, "tools");
            if (registry.hasResources()) writeEmptyCapability(gen, "resources");
            if (registry.hasPrompts())   writeEmptyCapability(gen, "prompts");
            gen.writeEndObject();
            gen.writeObjectFieldStart("serverInfo");
            gen.writeStringField("name", serverName);
            gen.writeStringField("version", serverVersion);
            gen.writeEndObject();
            if (instructions != null && !instructions.isBlank())
                gen.writeStringField("instructions", instructions);
            gen.writeEndObject();
        });
    }

    private static void writeEmptyCapability(JsonGenerator gen, String field) throws IOException {
        gen.writeObjectFieldStart(field);
        gen.writeBooleanField("listChanged", false);
        gen.writeEndObject();
    }

    private void handlePing(Response res, JsonNode id) {
        writeResult(res, id, gen -> { gen.writeStartObject(); gen.writeEndObject(); });
    }

    private void handleToolsList(Response res, JsonNode id) {
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeFieldName("tools");
            gen.writeRawValue(registry.toolsListJson());
            gen.writeEndObject();
        });
    }

    private void handleToolsCall(Response res, JsonNode id, JsonNode params) {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank())
            throw McpProtocolException.invalidParams("\"name\" is required");
        McpRegistry.RegisteredTool tool = registry.tool(name);
        if (tool == null)
            throw McpProtocolException.invalidParams("Unknown tool: " + name);

        ToolResponse result;
        String denied = tool.policy() != null ? tool.policy().check().get() : null;
        if (denied != null) {
            result = ToolResponse.error("Tool \"" + name + "\" denied: " + denied);
        } else {
            ToolArguments args = new ToolArguments(params.path("arguments"));
            try {
                result = tool.instance().call(args);
            } catch (Exception e) {
                result = ToolResponse.error("Tool \"" + name + "\" failed: " + e.getMessage());
            }
        }
        ToolResponse finalResult = result;
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeBooleanField("isError", finalResult.isError());
            gen.writeFieldName("content");
            McpContentWriter.writeContentArray(gen, finalResult.content());
            gen.writeEndObject();
        });
    }

    private void handleResourcesList(Response res, JsonNode id) {
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeFieldName("resources");
            gen.writeRawValue(registry.resourcesListJson());
            gen.writeEndObject();
        });
    }

    private void handleResourcesRead(Response res, JsonNode id, JsonNode params) throws Exception {
        String uri = params.path("uri").asText(null);
        if (uri == null || uri.isBlank())
            throw McpProtocolException.invalidParams("\"uri\" is required");
        McpRegistry.RegisteredResource resource = registry.resource(uri);
        if (resource == null)
            throw McpProtocolException.invalidParams("Unknown resource: " + uri);

        ResourceContents contents = resource.instance().read();
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("contents");
            McpContentWriter.writeResourceContents(gen, contents);
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    private void handlePromptsList(Response res, JsonNode id) {
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeFieldName("prompts");
            gen.writeRawValue(registry.promptsListJson());
            gen.writeEndObject();
        });
    }

    private void handlePromptsGet(Response res, JsonNode id, JsonNode params) throws Exception {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank())
            throw McpProtocolException.invalidParams("\"name\" is required");
        McpRegistry.RegisteredPrompt prompt = registry.prompt(name);
        if (prompt == null)
            throw McpProtocolException.invalidParams("Unknown prompt: " + name);

        PromptArguments args = new PromptArguments(params.path("arguments"));
        PromptMessage message = prompt.instance().render(args);
        writeResult(res, id, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("messages");
            McpContentWriter.writePromptMessage(gen, message);
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    // ── Envelope writers ─────────────────────────────────────────────────────

    private void writeResult(Response res, JsonNode id, McpJson.JsonWriter resultWriter) {
        String body = McpJson.buildString(gen -> {
            gen.writeStartObject();
            gen.writeStringField("jsonrpc", "2.0");
            gen.writeFieldName("id");
            writeId(gen, id);
            gen.writeFieldName("result");
            resultWriter.write(gen);
            gen.writeEndObject();
        });
        res.status(200).type(ContentType.JSON).body(body);
    }

    private void writeError(Response res, int httpStatus, JsonNode id, int code, String message) {
        String body = McpJson.buildString(gen -> {
            gen.writeStartObject();
            gen.writeStringField("jsonrpc", "2.0");
            gen.writeFieldName("id");
            writeId(gen, id);
            gen.writeObjectFieldStart("error");
            gen.writeNumberField("code", code);
            gen.writeStringField("message", message);
            gen.writeEndObject();
            gen.writeEndObject();
        });
        res.status(httpStatus).type(ContentType.JSON).body(body);
    }

    private static void writeId(JsonGenerator gen, JsonNode id) throws IOException {
        if (id == null || id.isNull() || id.isMissingNode()) { gen.writeNull(); return; }
        if (id.isTextual())              gen.writeString(id.asText());
        else if (id.isIntegralNumber())  gen.writeNumber(id.asLong());
        else if (id.isFloatingPointNumber()) gen.writeNumber(id.asDouble());
        else gen.writeNull();
    }
}
