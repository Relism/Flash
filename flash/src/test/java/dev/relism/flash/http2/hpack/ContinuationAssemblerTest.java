package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContinuationAssemblerTest {
  @Test
  void assemblesContiguousBlock() {
    ContinuationAssembler assembler = new ContinuationAssembler(16);
    assembler.begin(3, "abc".getBytes(StandardCharsets.US_ASCII), 0, 3, false);
    assembler.continuation(3, "def".getBytes(StandardCharsets.US_ASCII), 0, 3, true);
    assertTrue(assembler.isComplete());
    assertFalse(assembler.isActive());
    assertEquals(
        "abcdef", new String(assembler.buffer(), 0, assembler.length(), StandardCharsets.US_ASCII));
  }

  @Test
  void rejectsInterleavingWrongStreamAndOversizedBlocks() {
    ContinuationAssembler assembler = new ContinuationAssembler(4);
    assembler.begin(1, new byte[] {1}, 0, 1, false);
    assertThrows(Http2Exception.class, () -> assembler.begin(3, new byte[0], 0, 0, true));
    assertThrows(Http2Exception.class, () -> assembler.continuation(3, new byte[0], 0, 0, true));
    assertThrows(Http2Exception.class, () -> assembler.continuation(1, new byte[4], 0, 4, true));
  }

  @Test
  void boundsContinuationCount() {
    ContinuationAssembler assembler = new ContinuationAssembler(32);
    assembler.begin(1, new byte[0], 0, 0, false);
    for (int i = 0; i < Http2Limits.MAX_CONTINUATION_FRAMES_PER_BLOCK; i++) {
      assembler.continuation(1, new byte[0], 0, 0, false);
    }
    assertThrows(Http2Exception.class, () -> assembler.continuation(1, new byte[0], 0, 0, false));
  }
}
