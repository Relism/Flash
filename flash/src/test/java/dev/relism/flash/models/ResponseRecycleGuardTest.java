package dev.relism.flash.models;

import dev.relism.flash.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code EX-21}'s dev-mode use-after-recycle guard — mirrors {@code RequestRecycleGuardTest}. */
class ResponseRecycleGuardTest {

    @AfterEach
    void restoreProductionDefault() {
        Response.setPoisoningEnabledForTesting(false);
    }

    @Test
    void poisoningDisabled_recycledResponseStillAccessible() {
        Response.setPoisoningEnabledForTesting(false);
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertDoesNotThrow(r::getStatusCode);
    }

    @Test
    void poisoningEnabled_afterRecycle_getStatusCodeThrows() {
        Response.setPoisoningEnabledForTesting(true);
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, r::getStatusCode);
    }

    @Test
    void poisoningEnabled_afterRecycle_headerThrows() {
        Response.setPoisoningEnabledForTesting(true);
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.header("X", "Y"));
    }

    @Test
    void poisoningEnabled_afterRecycle_bodyThrows() {
        Response.setPoisoningEnabledForTesting(true);
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.body("x"));
    }

    @Test
    void poisoningEnabled_afterReset_accessibleAgain() {
        Response.setPoisoningEnabledForTesting(true);
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.recycle();
        assertThrows(IllegalStateException.class, r::getStatusCode);
        r.reset(200, ContentType.TEXT_PLAIN);
        assertDoesNotThrow(r::getStatusCode);
    }
}
