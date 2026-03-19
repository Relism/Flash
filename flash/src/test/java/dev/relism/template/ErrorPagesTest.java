package dev.relism.template;

import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestLine;
import dev.relism.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPagesTest {

    // --- helpers ---

    private Request mockRequest() {
        String path = "/api/test";
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        FastPathViews.RequestByteView pathView = new FastPathViews.RequestByteView(bytes, 0, bytes.length);

        String protocol = "HTTP/1.1";
        byte[] protoBytes = protocol.getBytes(StandardCharsets.UTF_8);
        FastPathViews.RequestByteView protoView = new FastPathViews.RequestByteView(protoBytes, 0, protoBytes.length);

        RequestLine line = new RequestLine(HttpMethod.GET, pathView, null, protoView, new HeaderMap());
        return new Request(line, new byte[0]);
    }

    // --- templates ---

    @Test
    void renderNotFound_generatesHtml() {
        Request req = mockRequest();
        byte[] html = ErrorPages.renderNotFound(req);
        String result = new String(html, StandardCharsets.UTF_8);

        assertTrue(result.contains("404"));
        assertTrue(result.contains("No route matched this request."));
        assertTrue(result.contains("/api/test")); // Injected path
        assertTrue(result.contains("GET")); // Injected method
        assertTrue(result.contains("footer-logo")); // Baked-in logo
    }

    @Test
    void renderException_generatesHtml() {
        Request req = mockRequest();
        Exception ex = new IllegalArgumentException("Invalid state in test application");
        byte[] html = ErrorPages.renderException(req, ex);
        String result = new String(html, StandardCharsets.UTF_8);

        assertTrue(result.contains("500"));
        assertTrue(result.contains("/api/test"));
        assertTrue(result.contains("IllegalArgumentException"));
        assertTrue(result.contains("Invalid state in test application"));
        assertTrue(result.contains("ErrorPagesTest.java")); // Stacktrace inclusion
    }
}
