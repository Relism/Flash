package dev.relism.api.multipart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * One part of a {@code multipart/form-data} body produced by {@link Multipart}.
 *
 * <p>Two internal states:
 * <ul>
 *   <li><b>Buffered</b> (text fields and {@link Multipart#parts()} results) — body is in a
 *       {@code byte[]}; {@link #stream()}, {@link #materialize()}, and {@link #text()} are all
 *       repeatable and free of I/O.</li>
 *   <li><b>Streaming</b> (file parts returned by {@link Multipart#file(String)}) — body is a
 *       bounded socket {@link InputStream}; call {@link #stream()} or {@link #materialize()} exactly
 *       once, before requesting the next part from {@link Multipart}.</li>
 * </ul>
 */
public final class Part {

    private final String      name;
    private final String      filename;    // null → text field
    private final String      contentType;
    private       byte[]      buf;         // non-null once materialised
    private final InputStream socketStream; // non-null only for streaming file parts

    static Part buffered(String name, String filename, String contentType, byte[] buf) {
        return new Part(name, filename, contentType, buf, null);
    }

    static Part streaming(String name, String filename, String contentType, InputStream stream) {
        return new Part(name, filename, contentType, null, stream);
    }

    private Part(String name, String filename, String contentType,
                 byte[] buf, InputStream socketStream) {
        this.name         = name;
        this.filename     = filename;
        this.contentType  = contentType;
        this.buf          = buf;
        this.socketStream = socketStream;
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    /** Field or file name from {@code Content-Disposition: form-data; name="..."}. */
    public String  name()        { return name; }

    /** Original filename from {@code filename="..."}, or {@code null} for text fields. */
    public String  filename()    { return filename; }

    /** {@code Content-Type} declared in the part headers, or {@code null} if absent. */
    public String  contentType() { return contentType; }

    /** {@code true} if this part has a {@code filename} attribute (i.e. a file upload). */
    public boolean isFile()      { return filename != null; }

    // -------------------------------------------------------------------------
    // Body access
    // -------------------------------------------------------------------------

    /**
     * Returns an {@link InputStream} over the part body.
     * For buffered parts: returns a fresh reader each call.
     * For streaming file parts: returns the raw socket stream — read once only.
     */
    public InputStream stream() {
        return buf != null ? new ByteArrayInputStream(buf) : socketStream;
    }

    /**
     * Materialises the body into a {@code byte[]}. Result is cached; safe to call repeatedly.
     * For streaming file parts: triggers a full read from the socket on first call.
     */
    public byte[] materialize() throws IOException {
        if (buf != null) return buf;
        return buf = socketStream.readAllBytes();
    }

    /**
     * Decodes the body as UTF-8. Materialises if needed; result is cached.
     * For streaming file parts: triggers a full read from the socket on first call.
     */
    public String text() throws IOException {
        return new String(materialize(), StandardCharsets.UTF_8);
    }
}
