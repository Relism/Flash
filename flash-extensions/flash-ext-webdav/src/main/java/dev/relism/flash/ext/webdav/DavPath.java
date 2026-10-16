package dev.relism.flash.ext.webdav;

import dev.relism.flash.http.HttpException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A path inside a mount: decoded, normalized, and never above the mount. The only form in which a
 * storage receives a path; each segment is a plain name, never {@code .}, {@code ..}, empty, or
 * holding a {@code /}. Names are kept exactly as the client sent them, Unicode normalization
 * included.
 */
public final class DavPath {

    /** The mount itself. */
    public static final DavPath ROOT = new DavPath(List.of());

    private final List<String> segments;

    private DavPath(List<String> segments) {
        this.segments = segments;
    }

    /** The rest of a request path after the mount, as sent: percent-encoded segments split by {@code /}. */
    static DavPath parse(String raw) {
        List<String> segments = new ArrayList<>();
        for (String encoded : raw.split("/")) {
            String segment = decode(encoded);
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) throw HttpException.forbidden("the path leaves the mount");
                segments.removeLast();
                continue;
            }
            segments.add(valid(segment));
        }
        return new DavPath(List.copyOf(segments));
    }

    /** The decoded names, from the mount down. */
    public List<String> segments() {
        return segments;
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** The last segment, or {@code ""} for the mount itself. */
    public String name() {
        return segments.isEmpty() ? "" : segments.getLast();
    }

    /** The enclosing collection; {@code null} for the mount itself. */
    public DavPath parent() {
        return segments.isEmpty() ? null : new DavPath(segments.subList(0, segments.size() - 1));
    }

    /** The member {@code name} of this collection. */
    public DavPath child(String name) {
        List<String> child = new ArrayList<>(segments);
        child.add(valid(name));
        return new DavPath(List.copyOf(child));
    }

    /** Whether {@code other} is this path or below it. */
    public boolean contains(DavPath other) {
        return other.segments.size() >= segments.size() && other.segments.subList(0, segments.size()).equals(segments);
    }

    /** This path under {@code to} instead of {@code from}, which contains it. */
    DavPath moved(DavPath from, DavPath to) {
        List<String> moved = new ArrayList<>(to.segments);
        moved.addAll(segments.subList(from.segments.size(), segments.size()));
        return new DavPath(List.copyOf(moved));
    }

    /** The path as an encoded URL path under {@code mount}, with a trailing {@code /} for a collection. */
    String href(String mount, boolean collection) {
        StringBuilder href = new StringBuilder(mount);
        for (String segment : segments) href.append('/').append(encode(segment));
        if (collection || segments.isEmpty()) href.append('/');
        return href.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DavPath path && path.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    /** {@code /a/b}, decoded; {@code /} for the mount. */
    @Override
    public String toString() {
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private static String valid(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
            throw HttpException.badRequest("not a resource name: " + name);
        return name;
    }

    static String decode(String encoded) {
        if (encoded.indexOf('%') < 0) return encoded;
        byte[] raw = encoded.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != '%') {
                bytes.write(raw[i]);
                continue;
            }
            if (i + 2 >= raw.length || !HexFormat.isHexDigit(raw[i + 1]) || !HexFormat.isHexDigit(raw[i + 2]))
                throw HttpException.badRequest("malformed percent-encoding in the path");
            bytes.write(HexFormat.fromHexDigit(raw[i + 1]) << 4 | HexFormat.fromHexDigit(raw[i + 2]));
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException notUtf8) {
            throw HttpException.badRequest("the path is not UTF-8");
        }
    }

    private static String encode(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~!$'()*+,;=:@".indexOf(c) >= 0)
                encoded.append((char) c);
            else encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return encoded.toString();
    }
}
