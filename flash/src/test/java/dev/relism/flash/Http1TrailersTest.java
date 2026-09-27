package dev.relism.flash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http1.Http1ResponseWriter;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionScratch;
import dev.relism.flash.transport.ScratchPool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Http1TrailersTest {
  @Test
  void requestTrailersBecomeVisibleOnlyAfterBodyEof() throws Exception {
    byte[] wire = ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
        + "3\r\nabc\r\n0\r\nGrpc-Status: 0\r\nX-Trace: done\r\n\r\n")
        .getBytes(StandardCharsets.US_ASCII);
    Request request = new RequestParser().parse(
        new BufferedByteSource(new ByteArrayInputStream(wire), null));

    assertThrows(IllegalStateException.class, request::trailers);
    assertEquals("abc", new String(request.body().bytes(), StandardCharsets.US_ASCII));
    assertEquals("0", request.trailers().first("grpc-status"));
    assertEquals("done", request.trailers().first("x-trace"));
  }

  @Test
  void responseTrailersUseChunkedRendering() throws Exception {
    Response response = new Response(200, "hello", ContentType.TEXT_PLAIN)
        .trailer("grpc-status", "0");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    Http1ResponseWriter.writeResponse(
        output, response, HttpMethod.GET, true, false, new ScratchPool().acquire());

    String wire = output.toString(StandardCharsets.US_ASCII);
    assertEquals(true, wire.contains("Transfer-Encoding: chunked\r\n"));
    assertEquals(true, wire.endsWith("5\r\nhello\r\n0\r\ngrpc-status: 0\r\n\r\n"));
  }

  @Test
  void pushStreamingAndTrailersShareTheSameHttp1Writer() throws Exception {
    Response response = new Response(200, ContentType.BINARY).streaming(stream -> {
      try {
        stream.write("one".getBytes(StandardCharsets.US_ASCII), 0, 3);
        stream.write("two".getBytes(StandardCharsets.US_ASCII), 0, 3);
        stream.trailer("grpc-status", "0");
      } catch (Exception failure) {
        throw new RuntimeException(failure);
      }
    });
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Http1ResponseWriter.writeResponse(
        output, response, HttpMethod.GET, true, false, new ScratchPool().acquire());

    String wire = output.toString(StandardCharsets.US_ASCII);
    assertEquals(true, wire.contains("one"));
    assertEquals(true, wire.contains("two"));
    assertEquals(true, wire.endsWith("0\r\ngrpc-status: 0\r\n\r\n"));
  }
}
