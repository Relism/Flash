package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.http2.Http2StreamException;
import org.junit.jupiter.api.Test;

class Http2StreamStateTest {
  @Test
  void everyTransitionCellIsExecutableOrTypedError() {
    for (Http2StreamState state : Http2StreamState.values()) {
      for (Http2StreamState.Event event : Http2StreamState.Event.values()) {
        if (Http2StreamState.isValid(state, event)) {
          Http2StreamState next = state.transition(1, event);
          assertEquals(true, next != null);
        } else {
          assertThrows(Http2StreamException.class, () -> state.transition(1, event));
        }
      }
    }
  }

  @Test
  void bodylessRequestAndResponseCloseStream() {
    Http2StreamState state =
        Http2StreamState.IDLE.transition(1, Http2StreamState.Event.RECV_HEADERS_ES);
    assertEquals(Http2StreamState.HALF_CLOSED_REMOTE, state);
    assertEquals(
        Http2StreamState.CLOSED, state.transition(1, Http2StreamState.Event.SEND_HEADERS_ES));
  }
}
