package dev.relism.flash.ext.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things a session has to get right: it reports itself expired early enough that it
 * cannot lapse midway through a request, and it hands back what a credential source stored on it
 * without interpreting any of it.
 */
class SessionTest {

    private static Session at(Instant expiry, Map<String, Object> attributes) {
        return new Session("s1", Map.of("sub", "u1"), expiry, attributes);
    }

    @Test
    void aSessionIsExpiredWellBeforeItsDeadline() {
        // The eager window is what stops a session from lapsing between the check and the handler.
        assertFalse(at(Instant.now().plusSeconds(120), Map.of()).isExpired());
        assertTrue(at(Instant.now().plusSeconds(10), Map.of()).isExpired());
        assertTrue(at(Instant.now().minusSeconds(1), Map.of()).isExpired());
    }

    @Test
    void attributesAreReturnedUninterpreted() {
        Session session = at(Instant.now().plusSeconds(60), Map.of("oidc.id_token", "abc", "n", 7));
        assertEquals("abc", session.attributeAsString("oidc.id_token"));
        assertEquals("7", session.attributeAsString("n"));
        assertEquals(7, session.attribute("n"));
        assertNull(session.attributeAsString("absent"));
    }

    @Test
    void aSessionWithoutAttributesIsUsableRatherThanNull() {
        assertNull(at(Instant.now().plusSeconds(60), null).attributeAsString("anything"));
    }

    @Test
    void claimsAndAttributesAreCopiedAndImmutable() {
        Map<String, Object> mutable = new HashMap<>(Map.of("k", "v"));
        Session session = at(Instant.now().plusSeconds(60), mutable);
        mutable.put("k", "changed");

        assertEquals("v", session.attributeAsString("k"));
        assertThrows(UnsupportedOperationException.class, () -> session.attributes().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> session.claims().put("x", "y"));
    }
}
