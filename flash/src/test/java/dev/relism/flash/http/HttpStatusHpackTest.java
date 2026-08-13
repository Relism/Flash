package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpStatusHpackTest {
  @Test
  void everyStatusHasAValidPrecompiledField() {
    for (HttpStatus status : HttpStatus.values()) {
      AtomicReference<String> decoded = new AtomicReference<>();
      byte[] block = status.hpackBytes();
      new HpackDecoder()
          .decode(
              block,
              0,
              block.length,
              (name, value, never) -> decoded.set(text(name) + "=" + text(value)));
      assertEquals(":status=" + status.code(), decoded.get());
    }
  }

  @Test
  void commonStaticStatusIsOneByte() {
    assertEquals(1, HttpStatus.OK.hpackBytes().length);
    assertEquals(0x88, HttpStatus.OK.hpackBytes()[0] & 0xff);
  }

  private static String text(ByteView view) {
    byte[] bytes = new byte[view.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
