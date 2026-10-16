package dev.relism.flash.ext.webdav;

import dev.relism.flash.http.ContentType;
import java.time.Instant;

/**
 * One resource as a storage describes it. {@link #file} and {@link #collection} fill in what can be
 * derived: an entity tag from the length and the modification time, the media type from the name.
 *
 * @param name        the last segment of its path; {@code ""} for the mount itself
 * @param length      bytes, for a file
 * @param etag        a strong entity tag, quotes included, that changes whenever the content does
 * @param contentType the media type of a file; {@code null} for a collection
 */
public record DavNode(String name, boolean collection, long length, Instant modified, Instant created, String etag,
                      String contentType) {

    public static DavNode file(String name, long length, Instant modified) {
        int dot = name.lastIndexOf('.');
        return new DavNode(name, false, length, modified, modified, etag(length, modified),
                ContentType.ofExtension(dot < 0 ? "" : name.substring(dot + 1)));
    }

    public static DavNode collection(String name, Instant modified) {
        return new DavNode(name, true, 0, modified, modified, etag(0, modified), null);
    }

    static String etag(long length, Instant modified) {
        return "\"" + Long.toHexString(length) + "-" + Long.toHexString(modified.getEpochSecond()) + "."
                + Integer.toHexString(modified.getNano()) + "\"";
    }
}
