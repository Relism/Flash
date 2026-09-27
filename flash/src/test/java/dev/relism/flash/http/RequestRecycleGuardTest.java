package dev.relism.flash.http;

import dev.relism.flash.http.HttpMethod;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The connection recycles a request or response after its handler in development mode; any use after that throws. */
class RequestRecycleGuardTest {

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
    void poisoningEnabled_freshRequest_accessibleNormally() {
        Request r = active();
        assertDoesNotThrow(r::path);
        assertDoesNotThrow(() -> r.header("Host"));
        assertDoesNotThrow(r::method);
    }

    @Test
    void poisoningEnabled_afterRecycle_methodThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::method);
    }

    @Test
    void poisoningEnabled_afterRecycle_pathThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::path);
    }

    @Test
    void poisoningEnabled_afterRecycle_headerThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.header("Host"));
    }

    @Test
    void poisoningEnabled_afterRecycle_paramThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.param("id"));
    }

    @Test
    void poisoningEnabled_afterRecycle_queryThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, () -> r.query("q"));
    }

    @Test
    void poisoningEnabled_afterRecycle_remoteAddressThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::remoteAddress);
    }

    @Test
    void poisoningEnabled_afterRecycle_isSecureThrows() {
        Request r = active();
        r.recycle();
        assertThrows(IllegalStateException.class, r::isSecure);
    }

    @Test
    void reusedAfterReset_becomesAccessibleAgain() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/first"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request r = new Request(line, new byte[0]);
        r.recycle();
        assertThrows(IllegalStateException.class, r::path);

        // Simulate the connection loop pulling this pooled instance back out for the next
        // request: Request.forParsed's reset() call re-activates it.
        RequestLine line2 = new RequestLine(HttpMethod.GET, viewOf("/second"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request reused = Request.forParsed(r, line2, RequestBody.empty(), null, null, null);
        assertSame(r, reused, "forParsed must reposition the same pooled instance, not allocate a new one");
        assertDoesNotThrow(reused::path);
        assertEquals("/second", reused.path());
    }
}
