package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRegistryTest {

    /** An app with nothing in it: the services the tools are built from. */
    @RegisterExtension
    static FlashTest empty = FlashTest.of(App::create);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void scan_findsAndPrecompilesToolsResourcesPrompts() throws Exception {
        McpRegistry registry = McpRegistry.scan("dev.relism.flash.ext.mcp.fixtures", empty.services(), null);

        assertTrue(registry.hasTools());
        assertTrue(registry.hasResources());
        assertTrue(registry.hasPrompts());

        JsonNode tools = MAPPER.readTree(registry.toolsListJson());
        assertEquals(2, tools.size()); // echo + boom
        JsonNode echo = findByField(tools, "name", "echo");
        assertEquals("Echoes the given text", echo.get("description").asText());
        assertEquals("object", echo.get("inputSchema").get("type").asText());
        assertEquals("string", echo.get("inputSchema").get("properties").get("text").get("type").asText());
        assertEquals("text", echo.get("inputSchema").get("required").get(0).asText());

        JsonNode resources = MAPPER.readTree(registry.resourcesListJson());
        assertEquals(1, resources.size());
        assertEquals("greeting://hello", resources.get(0).get("uri").asText());

        JsonNode prompts = MAPPER.readTree(registry.promptsListJson());
        assertEquals(1, prompts.size());
        assertEquals("summarize", prompts.get(0).get("name").asText());
        assertTrue(prompts.get(0).get("arguments").get(0).get("required").asBoolean());

        assertEquals("echo", registry.tool("echo").name());
        assertEquals("greeting://hello", registry.resource("greeting://hello").uri());
        assertEquals("summarize", registry.prompt("summarize").name());
    }

    @Test
    void scan_aPackageThatIsNotThere_failsTheBoot() {
        assertThrows(IllegalStateException.class,
                () -> McpRegistry.scan("dev.relism.flash.ext.mcp.doesnotexist", empty.services(), null));
    }

    private static JsonNode findByField(JsonNode array, String field, String value) {
        for (JsonNode n : array) if (value.equals(n.path(field).asText())) return n;
        throw new AssertionError("No entry with " + field + "=" + value);
    }
}
