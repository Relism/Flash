package dev.relism.flash.http2.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PseudoHeaderValidationTest {
  @Test
  void validRequest() {
    assertDoesNotThrow(
        () ->
            validate(
                ":method", "GET", ":scheme", "https", ":path", "/", ":authority", "example.com"));
  }

  @Test
  void rejectsPseudoAfterRegular() {
    rejects("x", "1", ":method", "GET", ":scheme", "https", ":path", "/", ":authority", "x");
  }

  @Test
  void rejectsUnknownAndDuplicatePseudoHeaders() {
    rejects(":method", "GET", ":scheme", "https", ":path", "/", ":authority", "x", ":other", "x");
    rejects(
        ":method", "GET", ":method", "POST", ":scheme", "https", ":path", "/", ":authority", "x");
  }

  @Test
  void rejectsMissingAndEmptyPseudoHeaders() {
    rejects(":method", "GET", ":scheme", "https", ":path", "/");
    rejects(":method", "GET", ":scheme", "https", ":path", "", ":authority", "x");
  }

  @Test
  void validatesConnectShape() {
    assertDoesNotThrow(() -> validate(":method", "CONNECT", ":authority", "example.com:443"));
    rejects(":method", "CONNECT", ":scheme", "https", ":authority", "example.com:443");
  }

  @Test
  void rejectsUppercaseForbiddenAndInvalidTeFields() {
    rejects(validWith("X-Test", "1"));
    rejects(validWith("connection", "close"));
    rejects(validWith("keep-alive", "timeout=5"));
    rejects(validWith("proxy-connection", "close"));
    rejects(validWith("transfer-encoding", "chunked"));
    rejects(validWith("upgrade", "websocket"));
    rejects(validWith("te", "gzip"));
    assertDoesNotThrow(() -> validate(validWith("te", "trailers")));
  }

  @Test
  void rejectsHostAuthorityConflict() {
    rejects(validWith("host", "other.example"));
    assertDoesNotThrow(() -> validate(validWith("host", "example.com")));
  }

  private static String[] validWith(String name, String value) {
    return new String[] {
      ":method", "GET", ":scheme", "https", ":path", "/", ":authority", "example.com", name, value
    };
  }

  private static void rejects(String... fields) {
    assertThrows(Http2StreamException.class, () -> validate(fields));
  }

  private static void validate(String... fields) {
    HpackHeaderBlock block = new HpackHeaderBlock();
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    for (int i = 0; i < fields.length; i += 2) {
      byte[] nameBytes = fields[i].getBytes(StandardCharsets.US_ASCII);
      byte[] valueBytes = fields[i + 1].getBytes(StandardCharsets.US_ASCII);
      name.reset(nameBytes, 0, nameBytes.length);
      value.reset(valueBytes, 0, valueBytes.length);
      block.accept(name, value, false);
    }
    new PseudoHeaders().validate(block, 1);
  }
}
