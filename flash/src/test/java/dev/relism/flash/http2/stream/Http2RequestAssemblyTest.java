package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Http2RequestAssemblyTest {
  @Test
  void assemblesProtocolNeutralRequestWithQueryAndAuthorityAlias() {
    Http2StreamTable table = new Http2StreamTable(1);
    Http2Stream stream = table.acquire(1);
    field(stream, ":method", "GET");
    field(stream, ":scheme", "https");
    field(stream, ":path", "/users/42?verbose=true");
    field(stream, ":authority", "example.com");
    field(stream, "x-trace", "abc");

    Request request = stream.assembleRequest(null, null);

    assertEquals(HttpMethod.GET, request.method());
    assertEquals("/users/42", request.path());
    assertEquals("true", request.query("verbose"));
    assertEquals("example.com", request.header("host"));
    assertEquals("example.com", request.header(":authority"));
    assertEquals("abc", request.header("X-Trace"));
    assertNull(request.remoteAddress());
  }

  private static void field(Http2Stream stream, String name, String value) {
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
    PooledSlice nameView = new PooledSlice();
    PooledSlice valueView = new PooledSlice();
    nameView.reset(nameBytes, 0, nameBytes.length);
    valueView.reset(valueBytes, 0, valueBytes.length);
    stream.headerBlock().accept(nameView, valueView, false);
  }
}
