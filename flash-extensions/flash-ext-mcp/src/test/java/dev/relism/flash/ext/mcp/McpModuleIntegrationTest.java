package dev.relism.flash.ext.mcp;

import dev.relism.flash.App;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end JSON-RPC lifecycle over the real Streamable HTTP endpoint — no OAuth2 involved. */
class McpModuleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Every test here is a stateless JSON-RPC call against the same server, so one boot for
    // the class rather than one per test.
    @RegisterExtension
    static FlashTest mcp = FlashTest.of(() -> App.create().install(new McpModule(
            McpConfig.builder("test-server")
                    .version("9.9.9")
                    .toolsPackage("dev.relism.flash.ext.mcp.fixtures")
                    .security(McpSecurity.NONE)
                    .build())));

    @Test
    void initialize_returnsProtocolVersionCapabilitiesAndServerInfo() throws Exception {
        JsonNode result = call(1, "initialize", "{}").get("result");

        assertTrue(result.has("protocolVersion"));
        assertEquals("test-server", result.get("serverInfo").get("name").asText());
        assertEquals("9.9.9", result.get("serverInfo").get("version").asText());
        assertTrue(result.get("capabilities").has("tools"));
        assertTrue(result.get("capabilities").has("resources"));
        assertTrue(result.get("capabilities").has("prompts"));
    }

    @Test
    void toolsList_containsRegisteredTools() throws Exception {
        JsonNode tools = call(2, "tools/list", "{}").get("result").get("tools");
        assertEquals(2, tools.size());
    }

    @Test
    void toolsCall_echo_returnsContent() throws Exception {
        JsonNode result = call(3, "tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hi there\"}}").get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("hi there", result.get("content").get(0).get("text").asText());
    }

    @Test
    void toolsCall_failingTool_returnsIsErrorResultNotProtocolError() throws Exception {
        JsonNode response = call(4, "tools/call", "{\"name\":\"boom\",\"arguments\":{}}");
        assertFalse(response.has("error"));
        JsonNode result = response.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("kaboom"));
    }

    @Test
    void toolsCall_unknownTool_returnsJsonRpcInvalidParamsError() throws Exception {
        JsonNode response = call(5, "tools/call", "{\"name\":\"nope\",\"arguments\":{}}");
        assertEquals(-32602, response.get("error").get("code").asInt());
    }

    @Test
    void resourcesRead_returnsTextContents() throws Exception {
        JsonNode result = call(6, "resources/read", "{\"uri\":\"greeting://hello\"}").get("result");
        assertEquals("hello world", result.get("contents").get(0).get("text").asText());
    }

    @Test
    void promptsGet_rendersMessage() throws Exception {
        JsonNode result = call(7, "prompts/get", "{\"name\":\"summarize\",\"arguments\":{\"text\":\"foo\"}}").get("result");
        assertEquals("Summarize: foo", result.get("messages").get(0).get("content").get("text").asText());
    }

    @Test
    void notification_returns202WithEmptyBody() {
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").expectStatus(202);
    }

    @Test
    void malformedJson_returns400ParseError() throws Exception {
        JsonNode json = MAPPER.readTree(post("not json").expectStatus(400).body());
        assertEquals(-32700, json.get("error").get("code").asInt());
    }

    @Test
    void unknownMethod_returnsJsonRpcMethodNotFound() throws Exception {
        JsonNode response = call(8, "not/a/method", "{}");
        assertEquals(-32601, response.get("error").get("code").asInt());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode call(int id, String method, String paramsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        return MAPPER.readTree(post(body).expectStatus(200).body());
    }

    private FlashResponse post(String body) {
        return mcp.request().json(body).post("/mcp");
    }
}
