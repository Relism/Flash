package dev.relism.flash.http;

import dev.relism.flash.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseRecycleGuardTest {


    @Test
    void poisoningEnabled_afterRecycle_getStatusCodeThrows() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, r::getStatusCode);
    }

    @Test
    void poisoningEnabled_afterRecycle_headerThrows() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.header("X", "Y"));
    }

    @Test
    void poisoningEnabled_afterRecycle_bodyThrows() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.body("x"));
    }

    @Test
    void poisoningEnabled_afterReset_accessibleAgain() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, r::getStatusCode);
        r.reset(200, ContentType.TEXT_PLAIN);
        assertDoesNotThrow(r::getStatusCode);
    }
}
