package dev.relism.flash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http1.Http1ResponseWriter;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionScratch;
import dev.relism.flash.transport.ScratchPool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  void streamingWritesChunksThenTrailersAndKeepsTheConnection() throws Exception {
    App app = App.create();
    app.get("/stream", (request, response) -> response.type(ContentType.BINARY).streaming(out -> {
      out.write("one".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      out.write("two".getBytes(StandardCharsets.US_ASCII));
      response.trailer("grpc-status", "0");
    }));
    Server server = Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).build());
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(5_000);
      byte[] get = "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
      socket.getOutputStream().write(get);
      String first = readThroughTrailers(socket.getInputStream());
      assertTrue(first.contains("Transfer-Encoding: chunked\r\n"), first);
      assertTrue(first.endsWith("3\r\none\r\n3\r\ntwo\r\n0\r\ngrpc-status: 0\r\n\r\n"), first);

      socket.getOutputStream().write(get);
      assertTrue(readThroughTrailers(socket.getInputStream()).startsWith("HTTP/1.1 200"));
    } finally {
      server.stop();
    }
  }

  @Test
  void middlewareSetsHeadersAfterTheHandlerAndAFailureBeforeWritingIsAnErrorResponse() throws Exception {
    App app = App.create();
    app.use((exchange, next) -> {
      next.handle(exchange);
      exchange.response().header("X-After", "set once the handler returned");
    });
    app.get("/stream", (request, response) -> response.streaming(out -> out.write('x')));
    app.get("/fixed", (request, response) -> "x");
    app.get("/refused", (request, response) -> response.streaming(out -> {
      throw HttpException.forbidden("refused before anything left");
    }));
    Server server = Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).build());
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> streamed = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/stream")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals("x", streamed.body());
      assertEquals("set once the handler returned", streamed.headers().firstValue("X-After").orElse(null));

      HttpResponse<String> fixed = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/fixed")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals("set once the handler returned", fixed.headers().firstValue("X-After").orElse(null));

      HttpResponse<String> refused = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/refused")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(403, refused.statusCode());
      assertTrue(refused.body().contains("refused before anything left"), refused.body());
    } finally {
      server.stop();
    }
  }

  private static String readThroughTrailers(InputStream in) throws Exception {
    StringBuilder wire = new StringBuilder();
    while (!wire.toString().endsWith("grpc-status: 0\r\n\r\n")) {
      int b = in.read();
      if (b < 0) throw new AssertionError("connection closed after: " + wire);
      wire.append((char) b);
    }
    return wire.toString();
  }
}
