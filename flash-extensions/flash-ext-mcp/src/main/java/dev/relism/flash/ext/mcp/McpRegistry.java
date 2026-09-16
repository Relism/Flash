package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.core.JsonGenerator;
import dev.relism.flash.exceptions.InitializationException;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.extension.FlashContext;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boot-time-built registry of tools/resources/prompts for one MCP server.
 *
 * <p>Everything static about the catalog — the {@code tools/list}/{@code resources/list}/
 * {@code prompts/list} JSON payloads — is assembled exactly once here via
 * {@link McpJson#buildString}, then spliced verbatim into responses at request time
 * ({@link McpDispatcher}) with {@link JsonGenerator#writeRawValue(String)}: no
 * re-serialization, no reflection, no databinding, and no per-request byte[]→String
 * conversion on the hot path — the string is already sitting in memory, built once at boot.
 */
final class McpRegistry {

    private static final String EMPTY_ARRAY = "[]";

    /** {@code policy} is {@code null} unless the tool carries @RolesAllowed/@ScopesAllowed. */
    record RegisteredTool(String name, McpTool instance, SecurityPolicy policy) {}
    record RegisteredResource(String uri, McpResource instance) {}
    record RegisteredPrompt(String name, McpPrompt instance) {}

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();
    private final Map<String, RegisteredResource> resources = new LinkedHashMap<>();
    private final Map<String, RegisteredPrompt> prompts = new LinkedHashMap<>();

    private String toolsListJson = EMPTY_ARRAY;
    private String resourcesListJson = EMPTY_ARRAY;
    private String promptsListJson = EMPTY_ARRAY;

    private McpRegistry() {}

    /** @param security {@code null} for a server running with {@link McpSecurity#NONE} */
    static McpRegistry scan(String packageName, FlashContext ctx, SecurityExtension security) {
        McpPackageScanner.ScanResult found = McpPackageScanner.scan(packageName);
        McpRegistry registry = new McpRegistry();

        for (Class<? extends McpTool> cls : found.tools()) {
            Tool ann = cls.getAnnotation(Tool.class);
            McpTool instance = instantiate(cls);
            instance.bind(ctx);
            if (security == null && SecurityPolicy.of(cls) != null)
                throw new InitializationException("MCP tool \"" + ann.name() + "\" declares security annotations, but the server runs with McpSecurity.NONE");
            SecurityPolicy policy = security == null ? null : security.policy(cls);
            if (registry.tools.putIfAbsent(ann.name(), new RegisteredTool(ann.name(), instance, policy)) != null)
                throw new InitializationException("Duplicate MCP tool name: \"" + ann.name() + "\"");
        }
        for (Class<? extends McpResource> cls : found.resources()) {
            Resource ann = cls.getAnnotation(Resource.class);
            McpResource instance = instantiate(cls);
            instance.bind(ctx);
            if (registry.resources.putIfAbsent(ann.uri(), new RegisteredResource(ann.uri(), instance)) != null)
                throw new InitializationException("Duplicate MCP resource uri: \"" + ann.uri() + "\"");
        }
        for (Class<? extends McpPrompt> cls : found.prompts()) {
            Prompt ann = cls.getAnnotation(Prompt.class);
            McpPrompt instance = instantiate(cls);
            instance.bind(ctx);
            if (registry.prompts.putIfAbsent(ann.name(), new RegisteredPrompt(ann.name(), instance)) != null)
                throw new InitializationException("Duplicate MCP prompt name: \"" + ann.name() + "\"");
        }

        if (!found.tools().isEmpty())
            registry.toolsListJson = McpJson.buildString(gen -> writeToolsArray(gen, found.tools()));
        if (!found.resources().isEmpty())
            registry.resourcesListJson = McpJson.buildString(gen -> writeResourcesArray(gen, found.resources()));
        if (!found.prompts().isEmpty())
            registry.promptsListJson = McpJson.buildString(gen -> writePromptsArray(gen, found.prompts()));

        return registry;
    }

    boolean hasTools()     { return !tools.isEmpty(); }
    boolean hasResources() { return !resources.isEmpty(); }
    boolean hasPrompts()   { return !prompts.isEmpty(); }

    String toolsListJson()     { return toolsListJson; }
    String resourcesListJson() { return resourcesListJson; }
    String promptsListJson()   { return promptsListJson; }

    RegisteredTool tool(String name)         { return tools.get(name); }
    RegisteredResource resource(String uri)  { return resources.get(uri); }
    RegisteredPrompt prompt(String name)     { return prompts.get(name); }

    // ── Boot-time JSON Schema / descriptor precompilation ───────────────────────

    private static void writeToolsArray(JsonGenerator gen, List<Class<? extends McpTool>> classes) throws IOException {
        gen.writeStartArray();
        for (Class<? extends McpTool> cls : classes) writeTool(gen, cls.getAnnotation(Tool.class));
        gen.writeEndArray();
    }

    private static void writeTool(JsonGenerator gen, Tool ann) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("name", ann.name());
        if (!ann.title().isBlank()) gen.writeStringField("title", ann.title());
        if (!ann.description().isBlank()) gen.writeStringField("description", ann.description());
        gen.writeFieldName("inputSchema");
        writeInputSchema(gen, ann.args());
        gen.writeEndObject();
    }

    private static void writeInputSchema(JsonGenerator gen, ToolArg[] args) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "object");
        gen.writeObjectFieldStart("properties");
        for (ToolArg arg : args) {
            gen.writeObjectFieldStart(arg.name());
            gen.writeStringField("type", arg.type().jsonSchemaType());
            if (!arg.description().isBlank()) gen.writeStringField("description", arg.description());
            gen.writeEndObject();
        }
        gen.writeEndObject();
        if (hasRequired(args)) {
            gen.writeArrayFieldStart("required");
            for (ToolArg arg : args) if (arg.required()) gen.writeString(arg.name());
            gen.writeEndArray();
        }
        gen.writeEndObject();
    }

    private static boolean hasRequired(ToolArg[] args) {
        for (ToolArg arg : args) if (arg.required()) return true;
        return false;
    }

    private static void writeResourcesArray(JsonGenerator gen, List<Class<? extends McpResource>> classes) throws IOException {
        gen.writeStartArray();
        for (Class<? extends McpResource> cls : classes) {
            Resource ann = cls.getAnnotation(Resource.class);
            gen.writeStartObject();
            gen.writeStringField("uri", ann.uri());
            gen.writeStringField("name", !ann.name().isBlank() ? ann.name() : ann.uri());
            if (!ann.description().isBlank()) gen.writeStringField("description", ann.description());
            gen.writeStringField("mimeType", ann.mimeType());
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }

    private static void writePromptsArray(JsonGenerator gen, List<Class<? extends McpPrompt>> classes) throws IOException {
        gen.writeStartArray();
        for (Class<? extends McpPrompt> cls : classes) {
            Prompt ann = cls.getAnnotation(Prompt.class);
            gen.writeStartObject();
            gen.writeStringField("name", ann.name());
            if (!ann.description().isBlank()) gen.writeStringField("description", ann.description());
            gen.writeArrayFieldStart("arguments");
            for (PromptArg arg : ann.args()) {
                gen.writeStartObject();
                gen.writeStringField("name", arg.name());
                if (!arg.description().isBlank()) gen.writeStringField("description", arg.description());
                gen.writeBooleanField("required", arg.required());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }

    private static <T> T instantiate(Class<T> cls) {
        try {
            Constructor<T> ctor = cls.getDeclaredConstructor();
            return ctor.newInstance();
        } catch (Exception e) {
            throw new InitializationException(
                    "Failed to instantiate " + cls.getName() +
                    " — ensure it has a public no-arg constructor", e);
        }
    }
}
