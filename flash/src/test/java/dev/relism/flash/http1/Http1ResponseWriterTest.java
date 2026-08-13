package dev.relism.flash.http1;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Response;
import dev.relism.flash.transport.ConnectionScratch;
import dev.relism.flash.transport.ScratchPool;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Http1ResponseWriterTest {

    private static ConnectionScratch scratch() {
        return new ScratchPool().acquire();
    }

    private static String write(Response response, HttpMethod method, boolean keepAlive, boolean sendDate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Http1ResponseWriter.writeResponse(out, response, method, keepAlive, sendDate, scratch());
        return out.toString(StandardCharsets.UTF_8);
    }

    // --- EX-14: HEAD ------------------------------------------------------------

    @Test
    void head_reportsContentLengthButWritesNoBody() throws IOException {
        Response response = new Response(200, "hello world", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.HEAD, true, false);

        assertTrue(raw.contains("Content-Length: 11\r\n"), raw);
        assertFalse(raw.contains("hello world"), raw);
    }

    @Test
    void get_writesTheBody_forComparison() throws IOException {
        Response response = new Response(200, "hello world", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, false);

        assertTrue(raw.contains("Content-Length: 11\r\n"), raw);
        assertTrue(raw.endsWith("hello world"), raw);
    }

    // --- EX-15: 204 / 304 / 1xx never carry Content-Length or a body ------------

    @Test
    void status204_omitsContentLengthAndBody() throws IOException {
        Response response = new Response(204, ContentType.NONE);
        response.setBody("should never appear");
        String raw = write(response, HttpMethod.GET, true, false);

        assertFalse(raw.contains("Content-Length"), raw);
        assertFalse(raw.contains("should never appear"), raw);
    }

    @Test
    void status304_omitsContentLengthAndBody() throws IOException {
        Response response = new Response(304, ContentType.NONE);
        response.setBody("should never appear");
        String raw = write(response, HttpMethod.GET, true, false);

        assertFalse(raw.contains("Content-Length"), raw);
        assertFalse(raw.contains("should never appear"), raw);
    }

    @Test
    void status1xx_omitsContentLengthAndBody() throws IOException {
        Response response = new Response(103, ContentType.NONE);
        response.setBody("should never appear");
        String raw = write(response, HttpMethod.GET, true, false);

        assertFalse(raw.contains("Content-Length"), raw);
        assertFalse(raw.contains("should never appear"), raw);
    }

    @Test
    void status200_stillCarriesContentLength_forComparison() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, false);
        assertTrue(raw.contains("Content-Length: 1\r\n"), raw);
    }

    // --- EX-15: ContentType.NONE omits the Content-Type line entirely -----------

    @Test
    void contentTypeNone_omitsContentTypeLine() throws IOException {
        Response response = new Response(200, ContentType.NONE);
        String raw = write(response, HttpMethod.GET, true, false);
        assertFalse(raw.contains("Content-Type"), raw);
    }

    @Test
    void contentTypeTextPlain_includesContentTypeLine() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, false);
        assertTrue(raw.contains("Content-Type: text/plain\r\n"), raw);
    }

    // --- EX-16: Date header -------------------------------------------------------

    @Test
    void sendDateTrue_includesDateHeader() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, true);
        assertTrue(raw.contains("Date: "), raw);
        // RFC 9110 IMF-fixdate, e.g. "Date: Tue, 03 Jun 2008 11:05:30 GMT\r\n"
        assertTrue(raw.matches("(?s).*Date: [A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT\\r\\n.*"), raw);
    }

    @Test
    void sendDateFalse_omitsDateHeader() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, false);
        assertFalse(raw.contains("Date: "), raw);
    }

    // --- Connection header --------------------------------------------------------

    @Test
    void keepAlive_writesKeepAliveConnectionHeader() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, true, false);
        assertTrue(raw.contains("Connection: keep-alive\r\n"), raw);
    }

    @Test
    void notKeepAlive_writesCloseConnectionHeader() throws IOException {
        Response response = new Response(200, "x", ContentType.TEXT_PLAIN);
        String raw = write(response, HttpMethod.GET, false, false);
        assertTrue(raw.contains("Connection: close\r\n"), raw);
    }
}
