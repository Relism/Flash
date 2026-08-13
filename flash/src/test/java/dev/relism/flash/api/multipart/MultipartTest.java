package dev.relism.flash.api.multipart;

import dev.relism.fpr.core.ByteView;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Http1HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final String BOUNDARY = "testboundary";

    private static byte[] body(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
            sb.append("--").append(BOUNDARY).append("\r\n").append(part).append("\r\n");
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String textPart(String name, String value) {
        return "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value;
    }

    private static String filePart(String name, String filename, String contentType, String value) {
        return "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n" + value;
    }

    private static Request request(byte[] bodyBytes) {
        String ct = "multipart/form-data; boundary=" + BOUNDARY;
        byte[] headerBuf = ("Content-Type: " + ct).getBytes(StandardCharsets.US_ASCII);
        Http1HeaderMap headers = new Http1HeaderMap();
        headers.reset(headerBuf, 0, headerBuf.length);
        RequestLine line = new RequestLine(HttpMethod.POST, viewOf("/upload"), null, viewOf("HTTP/1.1"), headers);
        return new Request(line, bodyBytes);
    }

    private static ByteView viewOf(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int  length()      { return b.length; }
            public byte byteAt(int i) { return b[i]; }
        };
    }

    // -------------------------------------------------------------------------
    // field()
    // -------------------------------------------------------------------------

    @Test
    void field_singleField_returnsValue() throws IOException {
        assertEquals("alice", Multipart.of(request(body(textPart("username", "alice")))).field("username"));
    }

    @Test
    void field_absent_returnsNull() throws IOException {
        assertNull(Multipart.of(request(body(textPart("username", "alice")))).field("missing"));
    }

    @Test
    void field_filePart_returnsNull() throws IOException {
        // file parts must not be returned by field()
        assertNull(Multipart.of(request(body(filePart("photo", "img.png", "image/png", "data")))).field("photo"));
    }

    @Test
    void field_multipleFields_anyOrder() throws IOException {
        // Text fields are buffered eagerly — accessible in any call order regardless of declaration order
        Multipart mp = Multipart.of(request(body(textPart("a", "1"), textPart("b", "2"), textPart("c", "3"))));
        assertEquals("3", mp.field("c"));
        assertEquals("1", mp.field("a")); // already in cache
        assertEquals("2", mp.field("b")); // already in cache
    }

    @Test
    void field_accessibleAfterFilePart() throws IOException {
        // "text" comes AFTER "file" — Multipart must drain the file body silently
        Multipart mp = Multipart.of(request(body(
                filePart("file", "f.bin", "application/octet-stream", "binary"),
                textPart("text", "hello"))));
        assertEquals("hello", mp.field("text"));
    }

    // -------------------------------------------------------------------------
    // file()
    // -------------------------------------------------------------------------

    @Test
    void file_filePart_returnsPartWithMetadata() throws IOException {
        Part p = Multipart.of(request(body(filePart("avatar", "me.jpg", "image/jpeg", "JFIF")))).file("avatar");

        assertNotNull(p);
        assertTrue(p.isFile());
        assertEquals("avatar",     p.name());
        assertEquals("me.jpg",     p.filename());
        assertEquals("image/jpeg", p.contentType());
    }

    @Test
    void file_absent_returnsNull() throws IOException {
        assertNull(Multipart.of(request(body(textPart("x", "y")))).file("photo"));
    }

    @Test
    void file_fieldPart_returnsNull() throws IOException {
        // text fields must not be returned by file()
        assertNull(Multipart.of(request(body(textPart("name", "bob")))).file("name"));
    }

    @Test
    void file_accessibleAfterTextField() throws IOException {
        // "file" comes AFTER "userId" — text field is buffered while scanning toward file
        Multipart mp = Multipart.of(request(body(
                textPart("userId", "42"),
                filePart("file", "doc.pdf", "application/pdf", "PDF"))));

        Part file = mp.file("file");
        assertNotNull(file);
        assertEquals("doc.pdf", file.filename());
        assertEquals("42", mp.field("userId")); // already cached
    }

    // -------------------------------------------------------------------------
    // Body access
    // -------------------------------------------------------------------------

    @Test
    void part_text_returnsUtf8Value() throws IOException {
        Part p = Multipart.of(request(body(textPart("note", "héllo")))).parts("note").getFirst();
        assertEquals("héllo", p.text());
    }

    @Test
    void part_bytes_returnsCorrectCopy() throws IOException {
        byte[] expected = "binary\0data".getBytes(StandardCharsets.UTF_8);
        Part p = Multipart.of(request(body(
                filePart("f", "f.bin", "application/octet-stream",
                         new String(expected, StandardCharsets.UTF_8))))).parts("f").getFirst();
        assertArrayEquals(expected, p.materialize());
    }

    @Test
    void part_stream_hasCorrectContent() throws IOException {
        Part p = Multipart.of(request(body(textPart("data", "streamed")))).parts("data").getFirst();
        assertArrayEquals("streamed".getBytes(StandardCharsets.UTF_8), p.stream().readAllBytes());
    }

    @Test
    void part_stream_isRepeatable_forBufferedParts() throws IOException {
        // Buffered parts (text fields, parts() results) can be read multiple times
        Part p = Multipart.of(request(body(textPart("k", "v")))).parts("k").getFirst();
        assertNotSame(p.stream(), p.stream()); // fresh ByteArrayInputStream each call
        assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), p.stream().readAllBytes());
    }

    @Test
    void part_bytes_isCached() throws IOException {
        Part p = Multipart.of(request(body(textPart("k", "v")))).parts("k").getFirst();
        assertSame(p.materialize(), p.materialize()); // second call returns cached array
    }

    // -------------------------------------------------------------------------
    // Multiple parts / parts()
    // -------------------------------------------------------------------------

    @Test
    void parts_sameNameMultiple_allReturned() throws IOException {
        Multipart mp = Multipart.of(request(body(
                textPart("tag", "alpha"),
                textPart("tag", "beta"),
                textPart("tag", "gamma"))));

        List<Part> tags = mp.parts("tag");
        assertEquals(3, tags.size());
        assertEquals("alpha", tags.get(0).text());
        assertEquals("beta",  tags.get(1).text());
        assertEquals("gamma", tags.get(2).text());
    }

    @Test
    void parts_allParts_inDeclarationOrder() throws IOException {
        List<Part> all = Multipart.of(request(body(
                textPart("first", "1"),
                filePart("second", "s.bin", "application/octet-stream", "2"),
                textPart("third", "3")))).parts();

        assertEquals(3, all.size());
        assertEquals("first",  all.get(0).name());
        assertEquals("second", all.get(1).name());
        assertEquals("third",  all.get(2).name());
    }

    // -------------------------------------------------------------------------
    // Large-body simulation (streaming correctness)
    // -------------------------------------------------------------------------

    @Test
    void file_bodyLargerThanWindow_streamedCorrectly() throws IOException {
        // Produce a body > 8 KB to exercise multi-refill window logic
        String large = "x".repeat(20_000);
        Part p = Multipart.of(request(body(
                filePart("big", "big.txt", "text/plain", large)))).file("big");

        assertNotNull(p);
        byte[] got = p.materialize();
        assertArrayEquals(large.getBytes(StandardCharsets.UTF_8), got);
    }

    @Test
    void field_afterLargeFile_drained_thenAccessible() throws IOException {
        // File part (>8 KB) before a text field — file must be drained, text must be accessible
        String large = "y".repeat(20_000);
        Multipart mp = Multipart.of(request(body(
                filePart("file", "f.bin", "application/octet-stream", large),
                textPart("meta", "value"))));

        assertEquals("value", mp.field("meta"));
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test
    void of_notMultipart_throws() {
        byte[] headerBuf = "Content-Type: application/json".getBytes(StandardCharsets.US_ASCII);
        Http1HeaderMap headers = new Http1HeaderMap();
        headers.reset(headerBuf, 0, headerBuf.length);
        RequestLine line = new RequestLine(HttpMethod.POST, viewOf("/"), null, viewOf("HTTP/1.1"), headers);
        Request req = new Request(line, new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> Multipart.of(req));
    }

    // -------------------------------------------------------------------------
    // EX-29: resource-exhaustion bounds
    // -------------------------------------------------------------------------

    @Test
    void field_bodyAboveMaxBufferedSize_throws() throws IOException {
        // MAX_MULTIPART_BUFFERED_PART_SIZE is 10 MiB — one byte over must be rejected, not
        // buffered whole into a single byte[].
        String tooBig = "z".repeat((int) dev.relism.flash.http.Http1Limits.MAX_MULTIPART_BUFFERED_PART_SIZE + 1);
        Multipart mp = Multipart.of(request(body(textPart("huge", tooBig))));
        assertThrows(IOException.class, () -> mp.field("huge"));
    }

    @Test
    void file_materializedDuringFullScan_aboveMaxBufferedSize_throws() throws IOException {
        String tooBig = "z".repeat((int) dev.relism.flash.http.Http1Limits.MAX_MULTIPART_BUFFERED_PART_SIZE + 1);
        Multipart mp = Multipart.of(request(body(filePart("f", "f.bin", "application/octet-stream", tooBig))));
        assertThrows(IOException.class, mp::parts);
    }

    @Test
    void scan_tooManyParts_throws() throws IOException {
        String[] parts = new String[dev.relism.flash.http.Http1Limits.MAX_MULTIPART_PARTS + 1];
        for (int i = 0; i < parts.length; i++) parts[i] = textPart("f" + i, "v");
        Multipart mp = Multipart.of(request(body(parts)));
        assertThrows(IOException.class, mp::parts);
    }

    @Test
    void partHeaders_tooManyHeaderLines_throws() throws IOException {
        StringBuilder part = new StringBuilder("Content-Disposition: form-data; name=\"x\"\r\n");
        for (int i = 0; i <= dev.relism.flash.http.Http1Limits.MAX_MULTIPART_PART_HEADER_COUNT; i++) {
            part.append("X-Extra-").append(i).append(": v\r\n");
        }
        part.append("\r\nbody");
        Multipart mp = Multipart.of(request(body(part.toString())));
        assertThrows(IOException.class, () -> mp.field("x"));
    }

    @Test
    void partHeaderLine_tooLong_throws() throws IOException {
        String longValue = "v".repeat(dev.relism.flash.http.Http1Limits.MAX_MULTIPART_HEADER_LINE_LENGTH + 1);
        String part = "Content-Disposition: form-data; name=\"x\"\r\n"
                + "X-Long: " + longValue + "\r\n\r\nbody";
        Multipart mp = Multipart.of(request(body(part)));
        assertThrows(IOException.class, () -> mp.field("x"));
    }

    @Test
    void withinAllLimits_stillWorksNormally() throws IOException {
        // Sanity check the bounds above don't false-positive on a normal small request.
        assertEquals("alice", Multipart.of(request(body(textPart("username", "alice")))).field("username"));
    }
}
