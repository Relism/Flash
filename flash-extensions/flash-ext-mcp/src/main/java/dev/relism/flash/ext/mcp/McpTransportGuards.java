package dev.relism.flash.ext.mcp;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.ContentType;
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
     * logged — same graceful-degradation shape as {@link McpSecurity#AUTO}.
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

    /**
     * Safety net around the whole MCP route: translates {@link HttpException} (thrown by
     * {@link #originGuard} or by {@code flash-ext-oidc}'s middleware) into a proper HTTP status
     * directly, instead of relying on the app's global exception handler — which defaults to a
     * generic 500 for every exception type unless the app owner overrides it (see
     * {@code AbstractRouter}'s default {@code exceptionHandler}). Keeps the MCP endpoint
     * correct out of the box regardless of what the rest of the app configures.
     */
    static Middleware httpExceptionGuard() {
        return next -> (req, res) -> {
            try {
                return next.handle(req, res);
            } catch (HttpException e) {
                String body = McpJson.buildString(gen -> {
                    gen.writeStartObject();
                    gen.writeStringField("error", e.getMessage());
                    gen.writeEndObject();
                });
                res.status(e.status()).type(ContentType.JSON).body(body);
                return null;
            }
        };
    }
}
