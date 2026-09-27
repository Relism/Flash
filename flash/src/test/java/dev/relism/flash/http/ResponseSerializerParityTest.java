package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http1.Http1ResponseWriter;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.transport.ScratchPool;
import dev.relism.fpr.core.ByteView;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseSerializerParityTest {
  @Test
  void bothProtocolsRenderTheSameResponseFields() throws Exception {
    Response response =
        new Response(201, "created", ContentType.JSON)
            .header("Cache-Control", "no-store")
            .header(new PreEncodedHeader("X-Trace", "abc"));

    ByteArrayOutputStream http1 = new ByteArrayOutputStream();
    Http1ResponseWriter.writeResponse(
        http1, response, HttpMethod.GET, true, false, new ScratchPool().acquire());
    Map<String, String> http1Fields = parseHttp1(http1.toString(StandardCharsets.US_ASCII));
    http1Fields.remove("connection");

    Http2ResponseWriter writer = new Http2ResponseWriter();
    writer.prepare(response, 1, false, false, true, false, false, 16_384, 4096, 65_535);
    int headerLength =
        ((writer.buffer()[0] & 0xff) << 16)
            | ((writer.buffer()[1] & 0xff) << 8)
            | (writer.buffer()[2] & 0xff);
    Map<String, String> http2Fields = new LinkedHashMap<>();
    new HpackDecoder()
        .decode(
            writer.buffer(),
            9,
            headerLength,
            (name, value, never) -> http2Fields.put(text(name), text(value)));
    http2Fields.remove(":status");

    assertEquals(http1Fields, http2Fields);
  }

  private static Map<String, String> parseHttp1(String message) {
    Map<String, String> fields = new LinkedHashMap<>();
    int end = message.indexOf("\r\n\r\n");
    String[] lines = message.substring(0, end).split("\r\n");
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      fields.put(lines[i].substring(0, colon).toLowerCase(), lines[i].substring(colon + 2));
    }
    return fields;
  }

  private static String text(ByteView view) {
    byte[] bytes = new byte[view.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
