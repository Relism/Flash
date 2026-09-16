package dev.relism.flash.ext.mcp;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Application middleware on the MCP route. Until this existed a consumer had no way to put rate
 * limiting, audit logging or tracing in front of {@code /mcp} — the chain was assembled entirely
 * inside the extension.
 */
class McpConfigMiddlewareTest {

    private static final AtomicInteger CALLS = new AtomicInteger();

    @RegisterExtension
    static FlashTest mcp = FlashTest.of(app -> app.install(new McpExtension(
            McpConfig.builder("middleware-server")
                    .version("1.0.0")
                    .toolsPackage("dev.relism.flash.ext.mcp.fixtures")
                    .security(McpSecurity.NONE)
                    .middleware(
                            next -> (req, res) -> {
                                CALLS.incrementAndGet();
                                return next.handle(req, res);
                            },
                            next -> (req, res) -> {
                                if ("deny".equals(req.header("X-Test-Gate"))) throw HttpException.forbidden();
                                return next.handle(req, res);
                            })
                    .build())));

    @Test
    void appMiddlewareRunsOnTheMcpRoute() {
        int before = CALLS.get();
        mcp.request()
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .post("/mcp")
                .expectStatus(200);
        assertEquals(before + 1, CALLS.get());
    }

    /**
     * The point of the hook: with {@link McpSecurity#NONE}, an app's own guard is the only thing
     * in front of the endpoint — which is how an app that does not authenticate with OAuth2
     * protects {@code /mcp} at all.
     */
    @Test
    void appMiddlewareCanRejectTheRequest() {
        mcp.request()
                .header("X-Test-Gate", "deny")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .post("/mcp")
                .expectStatus(403);
    }
}
