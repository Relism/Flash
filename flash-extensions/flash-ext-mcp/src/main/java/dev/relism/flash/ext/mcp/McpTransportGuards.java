package dev.relism.flash.ext.mcp;

import dev.relism.flash.http.HttpException;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Transport-level guards for the MCP Streamable HTTP endpoint. */
@Slf4j
final class McpTransportGuards {

    private McpTransportGuards() {}

    /**
     * Validates the {@code Origin} header per the Streamable HTTP transport's DNS-rebinding
     * protection requirement. Non-browser clients that omit {@code Origin} entirely are always
     * allowed through — only a <em>present but disallowed</em> value is rejected.
     *
     * <p>If {@code allowedOrigins} is empty, validation is skipped and a boot-time warning is
     * logged.
     */
    static Middleware originGuard(List<String> allowedOrigins) {
        if (allowedOrigins.isEmpty()) {
            log.warn("[flash-ext-mcp] No allowedOrigins configured — Origin header validation " +
                    "(DNS-rebinding protection) is DISABLED. Configure McpConfig.allowedOrigins(...) for production use.");
            return next -> next::handle;
        }
        return next -> (req, res) -> {
            String origin = req.header("Origin");
            if (origin != null && !allowedOrigins.contains(origin)) {
                throw HttpException.forbidden();
            }
            return next.handle(req, res);
        };
    }
}
