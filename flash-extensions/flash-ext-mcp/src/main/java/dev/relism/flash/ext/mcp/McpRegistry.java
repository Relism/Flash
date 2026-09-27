package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.core.JsonGenerator;
import dev.relism.flash.Scanner;
import dev.relism.flash.Services;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityPolicy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
    @SuppressWarnings("unchecked")
    static McpRegistry scan(String packageName, Services services, Security security) {
        McpRegistry registry = new McpRegistry();
        List<Class<? extends McpTool>> tools = new ArrayList<>();
        List<Class<? extends McpResource>> resources = new ArrayList<>();
        List<Class<? extends McpPrompt>> prompts = new ArrayList<>();
        for (Class<?> cls : Scanner.classes(packageName)) {
            if (Modifier.isAbstract(cls.getModifiers())) continue;
            if (McpTool.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Tool.class)) tools.add((Class<? extends McpTool>) cls);
            else if (McpResource.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Resource.class)) resources.add((Class<? extends McpResource>) cls);
            else if (McpPrompt.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Prompt.class)) prompts.add((Class<? extends McpPrompt>) cls);
        }
        if (tools.isEmpty() && resources.isEmpty() && prompts.isEmpty())
            throw new IllegalStateException("McpConfig.toolsPackage(\"" + packageName + "\") has no @Tool, @Resource or @Prompt class");

        for (Class<? extends McpTool> cls : tools) {
            Tool ann = cls.getAnnotation(Tool.class);
            if (security == null && SecurityPolicy.of(cls) != null)
                throw new IllegalStateException("MCP tool \"" + ann.name() + "\" declares security annotations, but the server runs with McpSecurity.NONE");
            McpTool instance = services.create(cls);
            instance.onInit();
            SecurityPolicy policy = security == null ? null : security.policy(cls);
            if (registry.tools.putIfAbsent(ann.name(), new RegisteredTool(ann.name(), instance, policy)) != null)
                throw new IllegalStateException("Duplicate MCP tool name: \"" + ann.name() + "\"");
        }
        for (Class<? extends McpResource> cls : resources) {
            Resource ann = cls.getAnnotation(Resource.class);
            McpResource instance = services.create(cls);
            instance.onInit();
            if (registry.resources.putIfAbsent(ann.uri(), new RegisteredResource(ann.uri(), instance)) != null)
                throw new IllegalStateException("Duplicate MCP resource uri: \"" + ann.uri() + "\"");
        }
        for (Class<? extends McpPrompt> cls : prompts) {
            Prompt ann = cls.getAnnotation(Prompt.class);
            McpPrompt instance = services.create(cls);
            instance.onInit();
            if (registry.prompts.putIfAbsent(ann.name(), new RegisteredPrompt(ann.name(), instance)) != null)
                throw new IllegalStateException("Duplicate MCP prompt name: \"" + ann.name() + "\"");
        }

        if (!tools.isEmpty())
            registry.toolsListJson = McpJson.buildString(gen -> writeToolsArray(gen, tools));
        if (!resources.isEmpty())
            registry.resourcesListJson = McpJson.buildString(gen -> writeResourcesArray(gen, resources));
        if (!prompts.isEmpty())
            registry.promptsListJson = McpJson.buildString(gen -> writePromptsArray(gen, prompts));

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
}
