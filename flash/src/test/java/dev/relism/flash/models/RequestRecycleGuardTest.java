package dev.relism.flash.models;

import dev.relism.flash.http.HttpMethod;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code EX-22}'s dev-mode use-after-recycle guard. Exercises the poisoning check directly via
 * {@code Request.setPoisoningEnabledForTesting} rather than the real {@code Flash.DEV} flag,
 * which is a {@code static final boolean} fixed once at JVM startup and cannot be toggled by an
 * individual test — see that field's own comment in {@code Request.java}.
 */
class RequestRecycleGuardTest {

    @AfterEach
    void restoreProductionDefault() {
        // Never leak the test override into other test classes sharing this JVM/fork.
        Request.setPoisoningEnabledForTesting(false);
    }

    private static ByteView viewOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
    }

    private static Request active() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/x"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        return new Request(line, new byte[0]);
    }

    @Test
    void poisoningDisabled_recycledRequestStillAccessible() {
        Request.setPoisoningEnabledForTesting(false);
        Request r = active();
        r.recycle();
        assertDoesNotThrow(r::method, "poisoning disabled (production default) must never throw");
    }

    @Test
    void poisoningEnabled_freshRequest_accessibleNormally() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        assertDoesNotThrow(r::path);
        assertDoesNotThrow(() -> r.header("Host"));
        assertDoesNotThrow(r::method);
    }

    @Test
    void poisoningEnabled_afterRecycle_methodThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::method);
    }

    @Test
    void poisoningEnabled_afterRecycle_pathThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::path);
    }

    @Test
    void poisoningEnabled_afterRecycle_headerThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.header("Host"));
    }

    @Test
    void poisoningEnabled_afterRecycle_paramThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.param("id"));
    }

    @Test
    void poisoningEnabled_afterRecycle_queryThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.query("q"));
    }

    @Test
    void poisoningEnabled_afterRecycle_remoteAddressThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::remoteAddress);
    }

    @Test
    void poisoningEnabled_afterRecycle_isSecureThrows() {
        Request.setPoisoningEnabledForTesting(true);
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::isSecure);
    }

    @Test
    void reusedAfterReset_becomesAccessibleAgain() {
        Request.setPoisoningEnabledForTesting(true);
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/first"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request r = new Request(line, new byte[0]);
        r.recycle();
        assertThrows(IllegalStateException.class, r::path);

        // Simulate the connection loop pulling this pooled instance back out for the next
        // request: Request.forParsed's reset() call re-activates it.
        RequestLine line2 = new RequestLine(HttpMethod.GET, viewOf("/second"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request reused = Request.forParsed(r, line2, RequestBody.empty(), null, null);
        assertSame(r, reused, "forParsed must reposition the same pooled instance, not allocate a new one");
        assertDoesNotThrow(reused::path);
        assertEquals("/second", reused.path());
    }
}
