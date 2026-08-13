package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http.HopByHopHeaders.Protocol;
import dev.relism.flash.models.MutableHeaderMap;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HopByHopHeaderTest {
  @Test
  void sharedPolicyCoversAllFourProtocolConversions() {
    for (Protocol sourceProtocol : Protocol.values()) {
      for (Protocol targetProtocol : Protocol.values()) {
        MutableHeaderMap source = new MutableHeaderMap();
        add(source, "connection", "x-private, keep-alive");
        add(source, "x-private", "secret");
        add(source, "upgrade", "websocket");
        add(source, "te", "trailers");
        add(source, "x-end-to-end", "yes");

        assertFalse(forward(source, "connection", "x-private", sourceProtocol, targetProtocol));
        assertFalse(forward(source, "x-private", "secret", sourceProtocol, targetProtocol));
        assertFalse(forward(source, "upgrade", "websocket", sourceProtocol, targetProtocol));
        assertTrue(forward(source, "x-end-to-end", "yes", sourceProtocol, targetProtocol));
        assertTrue(forward(source, "te", "trailers", sourceProtocol, Protocol.HTTP_2));
        assertFalse(forward(source, "te", "trailers", sourceProtocol, Protocol.HTTP_1_1));
      }
    }
  }

  private static boolean forward(
      MutableHeaderMap source,
      String name,
      String value,
      Protocol sourceProtocol,
      Protocol targetProtocol) {
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
    PooledSlice nameView = new PooledSlice();
    PooledSlice valueView = new PooledSlice();
    nameView.reset(nameBytes, 0, nameBytes.length);
    valueView.reset(valueBytes, 0, valueBytes.length);
    return HopByHopHeaders.shouldForward(
        source, nameView, valueView, sourceProtocol, targetProtocol);
  }

  private static void add(MutableHeaderMap headers, String name, String value) {
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
    headers.add(nameBytes, 0, nameBytes.length, valueBytes, 0, valueBytes.length);
  }
}
