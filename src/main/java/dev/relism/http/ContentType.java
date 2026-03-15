package dev.relism.http;

import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * Pre-compiled byte representations of common HTTP {@code Content-Type} values.
 * {@link #getBytes()} returns the pre-computed array directly — never allocates.
 */
@Getter
public enum ContentType {

    // Text
    TEXT_PLAIN          ("text/plain"),
    TEXT_HTML           ("text/html"),
    TEXT_CSS            ("text/css"),
    TEXT_JAVASCRIPT     ("text/javascript"),
    TEXT_XML            ("text/xml"),
    TEXT_CSV            ("text/csv"),
    TEXT_MARKDOWN       ("text/markdown"),
    TEXT_EVENT_STREAM   ("text/event-stream"),

    // Application
    JSON                ("application/json"),
    XML                 ("application/xml"),
    BINARY              ("application/octet-stream"),
    PDF                 ("application/pdf"),
    ZIP                 ("application/zip"),
    GZIP                ("application/gzip"),
    FORM_URLENCODED     ("application/x-www-form-urlencoded"),
    MULTIPART_FORM      ("multipart/form-data"),
    GRAPHQL             ("application/graphql"),
    NDJSON              ("application/x-ndjson"),
    MSGPACK             ("application/msgpack"),
    CBOR                ("application/cbor"),
    LD_JSON             ("application/ld+json"),

    // Image
    IMAGE_PNG           ("image/png"),
    IMAGE_JPEG          ("image/jpeg"),
    IMAGE_GIF           ("image/gif"),
    IMAGE_WEBP          ("image/webp"),
    IMAGE_SVG           ("image/svg+xml"),
    IMAGE_ICO           ("image/x-icon"),
    IMAGE_AVIF          ("image/avif"),

    // Font
    FONT_WOFF           ("font/woff"),
    FONT_WOFF2          ("font/woff2"),

    // Audio / Video
    AUDIO_MPEG          ("audio/mpeg"),
    AUDIO_OGG           ("audio/ogg"),
    VIDEO_MP4           ("video/mp4"),
    VIDEO_WEBM          ("video/webm");

    private final byte[] bytes;

    ContentType(String value) {
        this.bytes = value.getBytes(StandardCharsets.UTF_8);
    }
}
