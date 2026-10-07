package dev.relism.flash.http;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.Getter;

/**
 * Pre-compiled byte representations of common HTTP {@code Content-Type} values. {@code getBytes()}
 * returns the pre-computed array directly, never allocates.
 */
@Getter
public enum ContentType {
  NONE(""),

  // Text
  TEXT_PLAIN("text/plain"),
  TEXT_HTML("text/html"),
  TEXT_CSS("text/css"),
  TEXT_JAVASCRIPT("text/javascript"),
  TEXT_XML("text/xml"),
  TEXT_CSV("text/csv"),
  TEXT_MARKDOWN("text/markdown"),
  TEXT_EVENT_STREAM("text/event-stream"),

  // Application
  JSON("application/json"),
  XML("application/xml"),
  BINARY("application/octet-stream"),
  PDF("application/pdf"),
  ZIP("application/zip"),
  GZIP("application/gzip"),
  FORM_URLENCODED("application/x-www-form-urlencoded"),
  MULTIPART_FORM("multipart/form-data"),
  GRAPHQL("application/graphql"),
  NDJSON("application/x-ndjson"),
  PROTOBUF("application/protobuf"),
  MSGPACK("application/msgpack"),
  CBOR("application/cbor"),
  LD_JSON("application/ld+json"),

  // Image
  IMAGE_PNG("image/png"),
  IMAGE_JPEG("image/jpeg"),
  IMAGE_GIF("image/gif"),
  IMAGE_WEBP("image/webp"),
  IMAGE_SVG("image/svg+xml"),
  IMAGE_ICO("image/x-icon"),
  IMAGE_AVIF("image/avif"),

  // Font
  FONT_WOFF("font/woff"),
  FONT_WOFF2("font/woff2"),

  // Audio / Video
  AUDIO_MPEG("audio/mpeg"),
  AUDIO_OGG("audio/ogg"),
  VIDEO_MP4("video/mp4"),
  VIDEO_WEBM("video/webm");

  private final byte[] bytes;
  private final byte[] hpackBytes;
  private static final ContentType[] ALL = values();

  ContentType(String value) {
    this.bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) {
      this.hpackBytes = bytes;
    } else {
      ByteWriter out = new ByteWriter(32);
      HpackEncoder.writeLiteralWithNameIndex(out, 31, bytes, true);
      this.hpackBytes = Arrays.copyOf(out.array(), out.length());
    }
  }

  /** Whether this is text a person could read: {@code text/*} and the structured text formats. */
  public boolean isText() {
    return name().startsWith("TEXT_") || this == JSON || this == XML || this == LD_JSON
        || this == NDJSON || this == GRAPHQL || this == FORM_URLENCODED;
  }

  /** Precompiled HPACK {@code content-type} field, or an empty array for {@link #NONE}. */
  public byte[] getHpackBytes() {
    return hpackBytes;
  }

  /** The media type of a file named with {@code extension} (no dot, any case); text is UTF-8. */
  public static String ofExtension(String extension) {
    return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
      case "html", "htm" -> "text/html; charset=utf-8";
      case "js", "mjs" -> "text/javascript; charset=utf-8";
      case "css" -> "text/css; charset=utf-8";
      case "txt" -> "text/plain; charset=utf-8";
      case "csv" -> "text/csv; charset=utf-8";
      case "md" -> "text/markdown; charset=utf-8";
      case "json", "map" -> "application/json";
      case "webmanifest" -> "application/manifest+json";
      case "xml" -> "application/xml";
      case "pdf" -> "application/pdf";
      case "zip" -> "application/zip";
      case "wasm" -> "application/wasm";
      case "svg" -> "image/svg+xml";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "webp" -> "image/webp";
      case "avif" -> "image/avif";
      case "ico" -> "image/x-icon";
      case "woff" -> "font/woff";
      case "woff2" -> "font/woff2";
      case "ttf" -> "font/ttf";
      case "otf" -> "font/otf";
      case "mp3" -> "audio/mpeg";
      case "ogg" -> "audio/ogg";
      case "mp4" -> "video/mp4";
      case "webm" -> "video/webm";
      default -> "application/octet-stream";
    };
  }

  /** Finds the boot-time HPACK rendering for a response content-type byte array. */
  public static byte[] hpackBytesFor(byte[] value) {
    for (ContentType type : ALL) {
      if (type.bytes == value || Arrays.equals(type.bytes, value)) return type.hpackBytes;
    }
    return null;
  }
}
