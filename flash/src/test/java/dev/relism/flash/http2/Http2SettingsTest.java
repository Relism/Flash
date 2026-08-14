package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Http2SettingsTest {
  @Test
  void appliesEveryKnownSettingAndIgnoresUnknownIdentifiers() {
    Http2Settings settings = new Http2Settings();
    byte[] payload =
        payload(
            Http2Settings.HEADER_TABLE_SIZE,
            8_192,
            Http2Settings.ENABLE_PUSH,
            0,
            Http2Settings.MAX_CONCURRENT_STREAMS,
            123,
            Http2Settings.INITIAL_WINDOW_SIZE,
            70_000,
            Http2Settings.MAX_FRAME_SIZE,
            32_768,
            Http2Settings.MAX_HEADER_LIST_SIZE,
            99_999,
            0xf00d,
            42);
    int[] delta = new int[1];

    settings.apply(payload, 0, payload.length, value -> delta[0] = value);

    assertEquals(Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL, settings.headerTableSize());
    assertFalse(settings.pushEnabled());
    assertEquals(123, settings.maxConcurrentStreams());
    assertEquals(70_000, settings.initialWindowSize());
    assertEquals(32_768, settings.maxFrameSize());
    assertEquals(99_999, settings.maxHeaderListSize());
    assertEquals(70_000 - 65_535, delta[0]);
  }

  @Test
  void validatesBooleanSettingsInitialWindowAndFrameSize() {
    assertCode(Http2ErrorCode.PROTOCOL_ERROR, payload(Http2Settings.ENABLE_PUSH, 2));
    assertCode(
        Http2ErrorCode.PROTOCOL_ERROR, payload(Http2Settings.ENABLE_CONNECT_PROTOCOL, 2));
    assertCode(
        Http2ErrorCode.FLOW_CONTROL_ERROR, payload(Http2Settings.INITIAL_WINDOW_SIZE, 0x8000_0000));
    assertCode(Http2ErrorCode.PROTOCOL_ERROR, payload(Http2Settings.MAX_FRAME_SIZE, 16_383));
    assertCode(Http2ErrorCode.PROTOCOL_ERROR, payload(Http2Settings.MAX_FRAME_SIZE, 16_777_216));
  }

  @Test
  void initialWindowDeltaMayMakeOpenStreamsNegative() {
    Http2Settings settings = new Http2Settings();
    long[] windows = {10, 100, 65_535};

    settings.apply(
        payload(Http2Settings.INITIAL_WINDOW_SIZE, 1),
        0,
        6,
        delta -> {
          for (int i = 0; i < windows.length; i++) windows[i] += delta;
        });

    assertArrayEquals(new long[] {-65_524, -65_434, 1}, windows);
  }

  @Test
  void streamWindowOverflowRejectsWholeSettingsPayloadTransactionally() {
    Http2Settings settings = new Http2Settings();
    byte[] payload =
        payload(
            Http2Settings.ENABLE_PUSH, 0,
            Http2Settings.INITIAL_WINDOW_SIZE, 100_000);

    Http2Exception error =
        assertThrows(
            Http2Exception.class,
            () ->
                settings.apply(
                    payload,
                    0,
                    payload.length,
                    delta -> {
                      throw Http2Exception.FLOW_CONTROL_ERROR;
                    }));

    assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, error.errorCode());
    assertTrue(settings.pushEnabled(), "no earlier setting may leak through a failed update");
    assertEquals(65_535, settings.initialWindowSize());
  }

  @Test
  void malformedLengthAndEntryFloodAreRejected() {
    Http2Settings settings = new Http2Settings();
    assertSame(
        Http2Exception.FRAME_SIZE_ERROR,
        assertThrows(Http2Exception.class, () -> settings.apply(new byte[5], 0, 5, d -> {})));

    byte[] flood = new byte[(Http2Limits.MAX_SETTINGS_ENTRIES_PER_FRAME + 1) * 6];
    Http2Exception error =
        assertThrows(Http2Exception.class, () -> settings.apply(flood, 0, flood.length, d -> {}));
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, error.errorCode());
  }

  private static void assertCode(Http2ErrorCode code, byte[] payload) {
    Http2Settings settings = new Http2Settings();
    Http2Exception error =
        assertThrows(
            Http2Exception.class, () -> settings.apply(payload, 0, payload.length, d -> {}));
    assertEquals(code, error.errorCode());
  }

  private static byte[] payload(int... pairs) {
    byte[] settingsFrame = Http2TestFrames.settings(pairs);
    return Arrays.copyOfRange(settingsFrame, 9, settingsFrame.length);
  }
}
