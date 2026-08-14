package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContentTypeHpackTest {
  @Test
  void everyNonEmptyTypeHasAValidPrecompiledField() {
    for (ContentType type : ContentType.values()) {
      if (type == ContentType.NONE) continue;
      AtomicReference<String> decoded = new AtomicReference<>();
      byte[] block = type.getHpackBytes();
      new HpackDecoder()
          .decode(
              block,
              0,
              block.length,
              (name, value, never) -> decoded.set(text(name) + "=" + text(value)));
      assertEquals(
          "content-type=" + new String(type.getBytes(), StandardCharsets.US_ASCII), decoded.get());
    }
  }

  private static String text(ByteView view) {
    byte[] bytes = new byte[view.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
