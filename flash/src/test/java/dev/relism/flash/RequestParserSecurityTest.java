package dev.relism.flash;

import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.http.Http1Limits;
import dev.relism.flash.models.Request;
import dev.relism.flash.transport.BufferedByteSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One test per rejection rule added in HTTP/2 plan Phase 1 (EX-02, EX-03, EX-08, EX-18, EX-35,
 * EX-36), each asserting the specific status code {@link MalformedRequestException} carries —
 * not merely that some exception was thrown. {@code Http1Connection}/{@code ConnectionRunner} always closes the connection
 * after any of these (never keep-alive); that behaviour is exercised at the integration level
 * by {@code HttpServerTest}.
 */
class RequestParserSecurityTest {

    private static BufferedByteSource source(byte[] bytes) {
        return new BufferedByteSource(new ByteArrayInputStream(bytes), null);
    }

    private static Request parse(String raw) throws IOException {
        byte[] bytes = raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        return new RequestParser().parse(source(bytes));
    }

    private static MalformedRequestException expect(String raw) {
        return assertThrows(MalformedRequestException.class, () -> parse(raw));
    }

    // --- EX-02: Content-Length + Transfer-Encoding smuggling -------------------

    @Test
    void contentLengthAndTransferEncodingBothPresent_rejected400() {
        MalformedRequestException e = expect(
                "POST / HTTP/1.1\nHost: h\nContent-Length: 5\nTransfer-Encoding: chunked\n\nhello");
        assertEquals(400, e.status());
    }

    @Test
    void contentLengthAndTransferEncodingBothPresent_rejectedRegardlessOfOrder() {
        // The check must not be bypassable by which header appears first.
        MalformedRequestException e = expect(
                "POST / HTTP/1.1\nHost: h\nTransfer-Encoding: chunked\nContent-Length: 5\n\nhello");
        assertEquals(400, e.status());
    }

    @Test
    void duplicateContentLength_conflictingValues_rejected400() {
        MalformedRequestException e = expect(
                "POST / HTTP/1.1\nHost: h\nContent-Length: 5\nContent-Length: 6\n\nhello!");
        assertEquals(400, e.status());
    }

    @Test
    void duplicateContentLength_identicalValues_accepted() throws IOException {
        Request r = parse("POST / HTTP/1.1\nHost: h\nContent-Length: 5\nContent-Length: 5\n\nhello");
        assertEquals(5L, r.body().contentLength());
    }

    @Test
    void transferEncoding_finalCodingNotChunked_rejected501() {
        MalformedRequestException e = expect(
                "POST / HTTP/1.1\nHost: h\nTransfer-Encoding: gzip\n\n");
        assertEquals(501, e.status());
    }

    @Test
    void transferEncoding_chunkedNotFinal_rejected501() {
        // "chunked, gzip" — chunked must be the LAST coding (RFC 9112 §6.1).
        MalformedRequestException e = expect(
                "POST / HTTP/1.1\nHost: h\nTransfer-Encoding: chunked, gzip\n\n");
        assertEquals(501, e.status());
    }

    // --- EX-03: strict Content-Length parsing -----------------------------------

    @Test
    void contentLength_nonDigitSuffix_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nContent-Length: 5abc\n\n").status());
    }

    @Test
    void contentLength_leadingPlus_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nContent-Length: +5\n\n").status());
    }

    @Test
    void contentLength_leadingMinus_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nContent-Length: -1\n\n").status());
    }

    @Test
    void contentLength_empty_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nContent-Length: \n\n").status());
    }

    @Test
    void contentLength_overflowsLong_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nContent-Length: 99999999999999999999\n\n").status());
    }

    @Test
    void contentLength_aboveConfiguredMax_rejected413() {
        long tooLarge = Http1Limits.MAX_CONTENT_LENGTH + 1;
        assertEquals(413, expect("POST / HTTP/1.1\nHost: h\nContent-Length: " + tooLarge + "\n\n").status());
    }

    // --- EX-08: header/request-line limits --------------------------------------

    @Test
    void tooManyHeaders_rejected431() {
        StringBuilder sb = new StringBuilder("GET / HTTP/1.1\nHost: h\n");
        for (int i = 0; i < Http1Limits.MAX_HEADER_COUNT + 5; i++) sb.append("X-").append(i).append(": v\n");
        sb.append("\n");
        assertEquals(431, expect(sb.toString()).status());
    }

    @Test
    void headerNameTooLong_rejected431() {
        String name = "X-" + "a".repeat(Http1Limits.MAX_HEADER_NAME_LENGTH + 1);
        assertEquals(431, expect("GET / HTTP/1.1\nHost: h\n" + name + ": v\n\n").status());
    }

    @Test
    void headerValueTooLong_rejected431() {
        String value = "a".repeat(Http1Limits.MAX_HEADER_VALUE_LENGTH + 1);
        assertEquals(431, expect("GET / HTTP/1.1\nHost: h\nX-Big: " + value + "\n\n").status());
    }

    @Test
    void requestLineTooLong_rejected431() {
        String path = "/" + "a".repeat(Http1Limits.MAX_REQUEST_LINE_LENGTH + 1);
        assertEquals(431, expect("GET " + path + " HTTP/1.1\nHost: h\n\n").status());
    }

    // --- EX-18: bare CR / obs-fold -----------------------------------------------

    @Test
    void bareLfInsteadOfCrlf_headerLine_rejected() {
        // A '\r' not immediately followed by '\n' desynchronizes the parse.
        byte[] raw = "GET / HTTP/1.1\r\nHost: h\r\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(MalformedRequestException.class, () -> new RequestParser().parse(source(raw)));
    }

    @Test
    void obsFold_leadingWhitespaceContinuation_rejected400() {
        MalformedRequestException e = assertThrows(MalformedRequestException.class, () ->
                new RequestParser().parse(source(
                        "GET / HTTP/1.1\r\nHost: h\r\n Folded: continuation\r\n\r\n"
                                .getBytes(StandardCharsets.UTF_8))));
        assertEquals(400, e.status());
    }

    // --- header name tchar validation --------------------------------------------

    @Test
    void headerNameWithSpace_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nBad Name: v\n\n").status());
    }

    @Test
    void headerNameWithControlChar_rejected400() {
        String prefix = "GET / HTTP/1.1\r\nHost: h\r\nBad";
        String suffix = "Name: v\r\n\r\n";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.ISO_8859_1);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[prefixBytes.length + 1 + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, raw, 0, prefixBytes.length);
        raw[prefixBytes.length] = 0x01; // control character -- not a valid tchar
        System.arraycopy(suffixBytes, 0, raw, prefixBytes.length + 1, suffixBytes.length);
        assertThrows(MalformedRequestException.class, () -> new RequestParser().parse(source(raw)));
    }

    // --- EX-36: header line missing ':' -------------------------------------------

    @Test
    void headerLineMissingColon_rejected400() {
        assertEquals(400, expect("GET / HTTP/1.1\nHost: h\nNotAHeader\n\n").status());
    }

    // --- request-line rejections still carry the right status --------------------

    @Test
    void emptyMethod_rejected400() {
        assertEquals(400, expect(" / HTTP/1.1\nHost: h\n\n").status());
    }
}
