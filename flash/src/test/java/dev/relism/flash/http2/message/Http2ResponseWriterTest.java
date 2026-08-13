package dev.relism.flash.http2.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.models.Response;
import dev.relism.fpr.core.ByteView;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2ResponseWriterTest {
  @Test
  void serializesOrderedHeadersAndOneDataFrame() {
    Response response =
        new Response(200, "hello", ContentType.TEXT_PLAIN)
            .header("X-Trace", "abc")
            .header("Connection", "close")
            .header("Upgrade", "websocket");
    Http2ResponseWriter writer = new Http2ResponseWriter();

    assertTrue(writer.prepare(response, 3, false, false, true, false, true, 16_384, 4096, 65_535));
    Parsed parsed = parse(writer);

    assertEquals(List.of(FrameType.HEADERS, FrameType.DATA), parsed.types);
    assertEquals(FrameFlags.END_HEADERS, parsed.flags.get(0));
    assertEquals(FrameFlags.END_STREAM, parsed.flags.get(1));
    assertEquals("hello", new String(parsed.data, StandardCharsets.US_ASCII));
    assertEquals(
        List.of(":status=200", "content-type=text/plain", "content-length=5", "x-trace=abc"),
        decode(parsed.headerBlock));
  }

  @Test
  void splitsHeaderBlockIntoAdjacentContinuationFrames() {
    Response response =
        new Response(200, ContentType.NONE)
            .header("x-long", "abcdefghijklmnopqrstuvwxyz0123456789");
    Http2ResponseWriter writer = new Http2ResponseWriter();

    assertTrue(writer.prepare(response, 1, false, false, false, false, false, 12, 4096, 65_535));
    Parsed parsed = parse(writer);

    assertTrue(parsed.types.size() > 1);
    assertEquals(FrameType.HEADERS, parsed.types.get(0));
    for (int i = 1; i < parsed.types.size(); i++) {
      assertEquals(FrameType.CONTINUATION, parsed.types.get(i));
    }
    assertEquals(0, parsed.flags.get(0) & FrameFlags.END_HEADERS);
    assertTrue((parsed.flags.get(parsed.flags.size() - 1) & FrameFlags.END_HEADERS) != 0);
    assertEquals(
        List.of(":status=200", "x-long=abcdefghijklmnopqrstuvwxyz0123456789"),
        decode(parsed.headerBlock));
  }

  @Test
  void headAndBodyForbiddenStatusesEndOnHeaders() {
    for (Response response :
        List.of(
            new Response(200, "body", ContentType.TEXT_PLAIN),
            new Response(204, "body", ContentType.TEXT_PLAIN),
            new Response(304, "body", ContentType.TEXT_PLAIN))) {
      boolean head = response.getStatusCode() == 200;
      Http2ResponseWriter writer = new Http2ResponseWriter();
      assertTrue(
          writer.prepare(response, 1, head, false, true, false, false, 16_384, 4096, 65_535));
      Parsed parsed = parse(writer);
      assertEquals(List.of(FrameType.HEADERS), parsed.types);
      assertTrue((parsed.flags.get(0) & FrameFlags.END_STREAM) != 0);
      List<String> fields = decode(parsed.headerBlock);
      if (head) assertTrue(fields.contains("content-length=4"));
      else assertFalse(fields.stream().anyMatch(value -> value.startsWith("content-length=")));
    }
  }

  @Test
  void insufficientWindowDefersWithoutProducingPartialResponse() {
    Response response = new Response(200, "body", ContentType.TEXT_PLAIN);
    Http2ResponseWriter writer = new Http2ResponseWriter();
    assertFalse(writer.prepare(response, 1, false, false, true, false, false, 16_384, 3, 3));
    assertEquals(0, writer.length());
  }

  @Test
  void peerHeaderListLimitFailsTheStream() {
    Response response = new Response(200, ContentType.TEXT_PLAIN);
    Http2ResponseWriter writer = new Http2ResponseWriter();
    assertThrows(
        Http2StreamException.class,
        () -> writer.prepare(response, 5, false, false, true, false, false, 16_384, 41, 65_535));
  }

  @Test
  void flowControlledHeadPreservesKnownRepresentationLength() throws Exception {
    Response response =
        new Response(200, ContentType.BINARY)
            .stream(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 4);
    Http2ResponseWriter writer = new Http2ResponseWriter();

    writer.startFlowControlled(
        response, 1, true, false, true, false, false, 16_384, 4096, 16_384);
    Parsed parsed = parse(writer);

    assertEquals(List.of(FrameType.HEADERS), parsed.types);
    assertTrue(decode(parsed.headerBlock).contains("content-length=4"));
  }

  @Test
  void unknownLengthStreamUsesNativeDataWithoutTransferEncoding() throws Exception {
    Response response =
        new Response(200, ContentType.BINARY)
            .chunked(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
    Http2ResponseWriter writer = new Http2ResponseWriter();

    writer.startFlowControlled(
        response, 1, false, false, true, false, false, 16_384, 4096, 16_384);
    Parsed parsed = parse(writer);
    List<String> fields = decode(parsed.headerBlock);

    assertFalse(fields.stream().anyMatch(field -> field.startsWith("content-length=")));
    assertFalse(fields.stream().anyMatch(field -> field.startsWith("transfer-encoding=")));
    assertEquals(List.of(FrameType.HEADERS, FrameType.DATA), parsed.types);
  }

  private static Parsed parse(Http2ResponseWriter writer) {
    Parsed parsed = new Parsed();
    byte[] wire = writer.buffer();
    int position = 0;
    while (position < writer.length()) {
      int length =
          ((wire[position] & 0xff) << 16)
              | ((wire[position + 1] & 0xff) << 8)
              | (wire[position + 2] & 0xff);
      FrameType type = FrameType.fromCode(wire[position + 3] & 0xff);
      int flags = wire[position + 4] & 0xff;
      byte[] payload = Arrays.copyOfRange(wire, position + 9, position + 9 + length);
      parsed.types.add(type);
      parsed.flags.add(flags);
      if (type == FrameType.HEADERS || type == FrameType.CONTINUATION) {
        parsed.appendHeaders(payload);
      } else if (type == FrameType.DATA) {
        parsed.data = payload;
      }
      position += 9 + length;
    }
    parsed.headerBlock = Arrays.copyOf(parsed.headerBlock, parsed.headerLength);
    return parsed;
  }

  private static List<String> decode(byte[] block) {
    List<String> fields = new ArrayList<>();
    new HpackDecoder()
        .decode(
            block,
            0,
            block.length,
            (name, value, never) -> fields.add(text(name) + "=" + text(value)));
    return fields;
  }

  private static String text(ByteView view) {
    byte[] bytes = new byte[view.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  private static final class Parsed {
    final List<FrameType> types = new ArrayList<>();
    final List<Integer> flags = new ArrayList<>();
    byte[] headerBlock = new byte[64];
    int headerLength;
    byte[] data = new byte[0];

    void appendHeaders(byte[] fragment) {
      if (headerLength + fragment.length > headerBlock.length) {
        headerBlock = Arrays.copyOf(headerBlock, (headerLength + fragment.length) * 2);
      }
      System.arraycopy(fragment, 0, headerBlock, headerLength, fragment.length);
      headerLength += fragment.length;
    }
  }
}
