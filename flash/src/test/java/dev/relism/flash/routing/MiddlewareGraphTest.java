package dev.relism.flash.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiddlewareGraphTest {
    private static final Middleware NOOP = (exchange, next) -> next.handle(exchange);

    @Test
    void ordersNodesFromConstraintsNotRegistrationOrder() {
        MiddlewareKey auth = MiddlewareKey.of("auth");
        MiddlewareKey audit = MiddlewareKey.of("audit");
        Middleware authMiddleware = (exchange, next) -> next.handle(exchange);
        Middleware auditMiddleware = (exchange, next) -> next.handle(exchange);
        MiddlewareNode auditNode = MiddlewareNode.of(audit, auditMiddleware).after(auth);
        MiddlewareNode authNode = MiddlewareNode.of(auth, authMiddleware);

        Middleware[] ordered = MiddlewareGraph.order("GET /", List.of(auditNode, authNode));

        assertSame(authMiddleware, ordered[0]);
        assertSame(auditMiddleware, ordered[1]);
    }

    @Test
    void rejectsAbsentRequiredPredecessor() {
        MiddlewareNode audit = MiddlewareNode.of(MiddlewareKey.of("audit"), NOOP)
                .after(MiddlewareKey.of("auth"));

        assertThrows(IllegalStateException.class, () -> MiddlewareGraph.order("GET /", List.of(audit)));
    }

    @Test
    void acceptsAbsentOptionalPredecessor() {
        MiddlewareNode audit = MiddlewareNode.of(MiddlewareKey.of("audit"), NOOP)
                .afterIfPresent(MiddlewareKey.of("auth"));

        assertEquals(1, MiddlewareGraph.order("GET /", List.of(audit)).length);
    }
}
