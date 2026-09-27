package dev.relism.flash.http2.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Response;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class Http2LargeResponseTest {
  @Test
  void hundredMegabyteStreamUsesOneBoundedReusableFrameBuffer() throws Exception {
    long length = 100L * 1024 * 1024;
    Response response =
        new Response(200, ContentType.BINARY).stream(new RepeatingInputStream(length), length);
    Http2ResponseWriter writer = new Http2ResponseWriter();
    long written =
        writer.startFlowControlled(
            response, 1, false, false, true, false, false, 16_384, 32_768, 16_384);
    int largestBuffer = writer.buffer().length;
    while (!writer.finished()) {
      written += writer.resume(16_384, 16_384);
      largestBuffer = Math.max(largestBuffer, writer.buffer().length);
    }

    assertEquals(length, written);
    assertTrue(largestBuffer <= 65_536, "serialized storage must not scale with body length");
  }

  private static final class RepeatingInputStream extends InputStream {
    private long remaining;

    RepeatingInputStream(long remaining) {
      this.remaining = remaining;
    }

    @Override
    public int read() {
      if (remaining == 0) return -1;
      remaining--;
      return 0x5a;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (remaining == 0) return -1;
      int count = (int) Math.min(length, remaining);
      java.util.Arrays.fill(target, offset, offset + count, (byte) 0x5a);
      remaining -= count;
      return count;
    }
  }
}
