package dev.relism.ext.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.exceptions.HttpException;
import dev.relism.extension.ExtensionContext;
import dev.relism.extension.FlashApp;
import dev.relism.extension.FlashExtension;
import dev.relism.http.ContentType;

/**
 * Registers Jackson into the extension layer.
 *
 * <p>What this installs:
 * <ul>
 *   <li>Exposes the {@link ObjectMapper} in {@link ExtensionContext} — consumed by
 *       {@code flash-ext-openapi} and any extension that needs JSON serialization.</li>
 *   <li>Sets a global exception handler that maps {@link HttpException} to a JSON
 *       error body and catches all other exceptions as 500.</li>
 *   <li>Injects the mapper into {@link JacksonHandler} so all subclasses gain
 *       {@code bodyAs} and {@code json} without constructor boilerplate.</li>
 * </ul>
 *
 * <pre>{@code
 * FlashApp.of(new HttpServer(config))
 *     .install(new JacksonExtension());
 *
 * // Custom mapper:
 * ObjectMapper mapper = JsonMapper.builder()
 *     .addModule(new JavaTimeModule())
 *     .build();
 * .install(new JacksonExtension(mapper));
 * }</pre>
 */
public class JacksonExtension implements FlashExtension {

    private final ObjectMapper mapper;

    public JacksonExtension() {
        this(JsonMapper.builder().build());
    }

    public JacksonExtension(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void install(FlashApp app, ExtensionContext ctx) {
        ctx.provide(ObjectMapper.class, mapper);
        JacksonHandler.mapper = mapper;

        app.onException((ex, req, res) -> {
            if (ex instanceof HttpException e) {
                res.setStatusCode(e.status());
                res.setContentType(ContentType.JSON);
                return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            }
            res.setStatusCode(500);
            res.setContentType(ContentType.JSON);
            return "{\"error\":\"Internal Server Error\"}";
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
