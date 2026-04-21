package dev.relism.ext.limiter;

import dev.relism.ext.openapi.OpenApiContributor;
import dev.relism.ext.openapi.OpenApiContributorRegistry;
import dev.relism.ext.openapi.OpenApiOperationContribution;
import dev.relism.ext.openapi.OpenApiResponseContribution;
import dev.relism.extension.FlashContext;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimiterOpenApiInteropTest {

    @GET("/limited")
    @Limit(requests = 10, window = 1)
    static class LimitedHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/plain")
    static class PlainHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Test
    void registersContributor_whenOpenApiRegistryExists() {
        FlashContext ctx = new FlashContext();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        ctx.provide(OpenApiContributorRegistry.class, registry);

        new LimiterExtension().routes(null, ctx);

        assertEquals(1, registry.contributors().size());
    }

    @Test
    void limitedHandler_contributesHeadersAnd429() {
        FlashContext ctx = new FlashContext();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        ctx.provide(OpenApiContributorRegistry.class, registry);
        new LimiterExtension().routes(null, ctx);

        OpenApiContributor contributor = registry.contributors().getFirst();
        OpenApiOperationContribution operation = contributor.operationFor(LimitedHandler.class);

        OpenApiResponseContribution all = operation.allResponses();
        assertNotNull(all);
        assertTrue(all.headers().containsKey("X-RateLimit-Limit"));
        assertTrue(all.headers().containsKey("X-RateLimit-Remaining"));
        assertTrue(all.headers().containsKey("X-RateLimit-Reset"));

        OpenApiResponseContribution tooMany = operation.responses().get(429);
        assertNotNull(tooMany);
        assertEquals("Too Many Requests", tooMany.description());
        assertTrue(tooMany.headers().containsKey("Retry-After"));
    }

    @Test
    void plainHandler_hasNoContribution() {
        FlashContext ctx = new FlashContext();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        ctx.provide(OpenApiContributorRegistry.class, registry);
        new LimiterExtension().routes(null, ctx);

        OpenApiContributor contributor = registry.contributors().getFirst();
        OpenApiOperationContribution operation = contributor.operationFor(PlainHandler.class);

        assertTrue(operation.isEmpty());
        assertFalse(operation.responses().containsKey(429));
    }
}
