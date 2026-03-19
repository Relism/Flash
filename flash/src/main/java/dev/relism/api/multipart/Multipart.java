package dev.relism.api.multipart;

import dev.relism.models.Request;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lazy streaming {@code multipart/form-data} parser.
 *
 * <p>Reads from {@code body.stream()} — the request body is <em>never</em> fully materialised.
 * Text fields are buffered eagerly on first encounter (they are small by definition). File part
 * bodies are exposed as zero-copy {@link InputStream}s backed directly by the socket stream and
 * must be consumed before the next call to any scan method.
 *
 * <pre>{@code
 * Multipart mp = Multipart.of(req);
 *
 * // Text fields — buffered eagerly, accessible in any call order
 * String userId = mp.field("userId");
 * String width  = mp.field("width");
 *
 * // File parts — zero-copy socket stream; consume before requesting the next file
 * Part avatar = mp.file("avatar");
 * Files.copy(avatar.stream(), destination);
 *
 * // Explicit materialization of a file body (opt-in heap allocation)
 * byte[] data = mp.file("doc").materialize();
 *
 * // Collect everything at once — eagerly buffers all bodies (accept the memory cost)
 * List<Part> files = mp.parts("files");
 * }</pre>
 *
 * <p><b>Scan ordering:</b> {@link #field} and {@link #file} scan forward through the stream.
 * Parts already passed cannot be re-read. Text fields encountered while scanning toward a file
 * are buffered silently; file bodies encountered while scanning toward a text field are drained
 * silently. Use {@link #parts()} only if you need everything and accept full materialisation.
 *
 * <p><b>Thread safety:</b> not thread-safe; one instance per request.
 */
public final class Multipart {

    private static final int BUF_CAP = 8192;

    private final InputStream src;
    private final byte[]      crlfBound; // "\r\n--<boundary>"

    private final byte[] win;
    private int     wPos   = 0;
    private int     wLen   = 0;
    private boolean srcEof = false;
    private boolean done   = false;

    private final List<Part> scanned = new ArrayList<>();
    private PartBodyStream   active  = null; // open file stream; must be drained before next scan

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    private Multipart(InputStream src, String boundary) throws IOException {
        this.src       = src;
        this.crlfBound = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.win       = new byte[BUF_CAP + crlfBound.length];
        skipFromWindow(2 + boundary.length() + 2); // "--boundary\r\n"
    }

    /**
     * Creates a parser for {@code request}. Uses {@code body.stream()} — zero heap allocation.
     *
     * @throws IllegalArgumentException if the request is not multipart or boundary is missing
     * @throws IOException              if the initial stream read fails
     */
    public static Multipart of(Request req) throws IOException {
        String ct = req.header("Content-Type");
        if (ct == null || !ct.startsWith("multipart/"))
            throw new IllegalArgumentException("Not a multipart request (Content-Type: " + ct + ")");
        String boundary = extractParam(ct, "boundary");
        if (boundary == null)
            throw new IllegalArgumentException("Missing boundary in Content-Type: " + ct);
        return new Multipart(req.body().stream(), boundary);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the text value of the first field named {@code name}, or {@code null}.
     * Scans forward; text parts encountered along the way are buffered, file bodies are drained.
     */
    public String field(String name) throws IOException {
        for (Part p : scanned)
            if (name.equals(p.name()) && !p.isFile()) return p.text();
        while (!done) {
            Part p = scanNext(false);
            if (p != null && name.equals(p.name()) && !p.isFile()) return p.text();
        }
        return null;
    }

    /**
     * Returns the first file part named {@code name}, or {@code null}.
     * Scans forward; text parts encountered along the way are buffered, earlier file bodies
     * are drained. The returned part's stream must be consumed before the next scan call.
     */
    public Part file(String name) throws IOException {
        for (Part p : scanned)
            if (name.equals(p.name()) && p.isFile()) return p; // already materialized via parts()
        while (!done) {
            Part p = scanNext(false);
            if (p != null && name.equals(p.name()) && p.isFile()) return p;
        }
        return null;
    }

    /**
     * Returns all parts named {@code name} in declaration order.
     * Forces a full scan; all file bodies are materialised into heap.
     */
    public List<Part> parts(String name) throws IOException {
        scanAll();
        List<Part> result = new ArrayList<>();
        for (Part p : scanned) if (name.equals(p.name())) result.add(p);
        return result;
    }

    /**
     * Returns all parts in declaration order.
     * Forces a full scan; all file bodies are materialised into heap.
     */
    public List<Part> parts() throws IOException {
        scanAll();
        return List.copyOf(scanned);
    }

    // -------------------------------------------------------------------------
    // Scan
    // -------------------------------------------------------------------------

    private void scanAll() throws IOException { while (!done) scanNext(true); }

    /**
     * Scans the next part.
     *
     * @param materialize if {@code true}, file part bodies are buffered immediately
     *                    (used by {@link #parts()} and {@link #parts(String)})
     */
    private Part scanNext(boolean materialize) throws IOException {
        if (done) return null;
        drainActive();
        if (done) return null;

        Map<String, String> headers = readPartHeaders();
        if (headers == null) { done = true; return null; }

        String disp     = headers.get("content-disposition");
        String name     = extractParam(disp, "name");
        String filename = extractParam(disp, "filename");
        String ct       = headers.get("content-type");

        active = new PartBodyStream();

        Part p;
        if (filename != null && !materialize) {
            // File part — expose streaming body; not cached (stream is consumed once)
            p = Part.streaming(name, filename, ct, active);
        } else {
            // Text part, or full-scan path: buffer body now
            byte[] body = active.readAllBytes();
            active = null;
            p = Part.buffered(name, filename, ct, body);
            scanned.add(p);
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // PartBodyStream — inner class sharing the window buffer
    // -------------------------------------------------------------------------

    final class PartBodyStream extends InputStream {
        boolean bodyEof = false;

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (bodyEof) return -1;
            refill();
            if (wLen == 0) { seal(); return -1; }

            int delimAt = findDelim(wPos, wLen);
            int available;
            if (delimAt >= 0) {
                available = delimAt - wPos;
                if (available == 0) { advancePastBoundary(); seal(); return -1; }
            } else {
                available = srcEof ? wLen : Math.max(0, wLen - (crlfBound.length - 1));
                if (available == 0) { refill(); return read(buf, off, len); }
            }

            int n = Math.min(len, available);
            System.arraycopy(win, wPos, buf, off, n);
            wPos += n;
            wLen -= n;
            if (delimAt >= 0 && wPos == delimAt) { advancePastBoundary(); seal(); }
            return n;
        }

        @Override
        public int read() throws IOException {
            byte[] b = {0};
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        /** Marks this stream as exhausted and releases the active slot. */
        private void seal() { bodyEof = true; if (active == this) active = null; }
    }

    // -------------------------------------------------------------------------
    // Window management
    // -------------------------------------------------------------------------

    private void refill() throws IOException {
        if (srcEof) return;
        if (wPos > 0) { System.arraycopy(win, wPos, win, 0, wLen); wPos = 0; }
        int space = win.length - wLen;
        if (space > 0) {
            int n = src.read(win, wLen, space);
            if (n < 0) srcEof = true; else wLen += n;
        }
    }

    private void skipFromWindow(int bytes) throws IOException {
        int rem = bytes;
        while (rem > 0) { refill(); int s = Math.min(rem, wLen); wPos += s; wLen -= s; rem -= s; }
    }

    /** Drains the active file stream so we can advance to the next part. */
    private void drainActive() throws IOException {
        if (active == null || active.bodyEof) return;
        PartBodyStream ps = active;
        byte[] sink = new byte[BUF_CAP];
        while (ps.read(sink) >= 0) {}
    }

    private void advancePastBoundary() throws IOException {
        wPos += crlfBound.length;
        wLen -= crlfBound.length;
        refill();
        if (wLen >= 2) {
            if (win[wPos] == '-' && win[wPos + 1] == '-') {
                done = true;
                wPos += 2; wLen -= 2;
                if (wLen >= 2) { wPos += 2; wLen -= 2; } // optional trailing \r\n
            } else {
                wPos += 2; wLen -= 2; // \r\n before next part's headers
            }
        }
    }

    // -------------------------------------------------------------------------
    // Header parsing
    // -------------------------------------------------------------------------

    private Map<String, String> readPartHeaders() throws IOException {
        Map<String, String> map = new HashMap<>();
        while (true) {
            String line = readLine();
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0)
                map.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
        }
        return map.isEmpty() ? null : map;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            refill();
            if (wLen == 0) return sb.length() > 0 ? sb.toString() : null;

            int end = wPos + wLen;
            for (int i = wPos; i < end - 1; i++) {
                if (win[i] == '\r' && win[i + 1] == '\n') {
                    sb.append(new String(win, wPos, i - wPos, StandardCharsets.UTF_8));
                    int consumed = i - wPos + 2;
                    wPos += consumed; wLen -= consumed;
                    return sb.toString();
                }
            }
            // No \r\n found yet — keep the last byte (might be a split \r\n) and refill
            int append = srcEof ? wLen : (wLen > 0 ? wLen - 1 : 0);
            if (append > 0) {
                sb.append(new String(win, wPos, append, StandardCharsets.UTF_8));
                wPos += append; wLen -= append;
            }
            if (srcEof && wLen > 0) {
                sb.append(new String(win, wPos, wLen, StandardCharsets.UTF_8));
                wPos += wLen; wLen = 0;
                return sb.toString();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private int findDelim(int from, int searchLen) {
        int last = from + searchLen - crlfBound.length;
        outer:
        for (int i = from; i <= last; i++) {
            for (int j = 0; j < crlfBound.length; j++)
                if (win[i + j] != crlfBound[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static String extractParam(String header, String param) {
        if (header == null) return null;
        int idx = header.indexOf(param + "=");
        if (idx < 0) return null;
        idx += param.length() + 1;
        if (idx < header.length() && header.charAt(idx) == '"') {
            int end = header.indexOf('"', idx + 1);
            return end >= 0 ? header.substring(idx + 1, end) : null;
        }
        int end = header.indexOf(';', idx);
        return end >= 0 ? header.substring(idx, end).trim() : header.substring(idx).trim();
    }
}
