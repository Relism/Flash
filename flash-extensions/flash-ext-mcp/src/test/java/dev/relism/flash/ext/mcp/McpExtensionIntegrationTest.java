package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.extension.FlashApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end JSON-RPC lifecycle over the real Streamable HTTP endpoint — no OAuth2 involved. */
class McpExtensionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlashApp app;
    private String mcpUrl;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        mcpUrl = "http://127.0.0.1:" + port + "/mcp";
        client = HttpClient.newHttpClient();

        McpConfig config = McpConfig.builder("test-server")
                .version("9.9.9")
                .toolsPackage("dev.relism.flash.ext.mcp.fixtures")
                .security(McpSecurity.NONE)
                .build();

        app = FlashApp.create(port);
        app.install(new McpExtension(config));
        app.start();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

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
    void notification_returns202WithEmptyBody() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        HttpResponse<String> resp = post(body);
        assertEquals(202, resp.statusCode());
    }

    @Test
    void malformedJson_returns400ParseError() throws Exception {
        HttpResponse<String> resp = post("not json");
        assertEquals(400, resp.statusCode());
        JsonNode json = MAPPER.readTree(resp.body());
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
        HttpResponse<String> resp = post(body);
        assertEquals(200, resp.statusCode());
        return MAPPER.readTree(resp.body());
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
