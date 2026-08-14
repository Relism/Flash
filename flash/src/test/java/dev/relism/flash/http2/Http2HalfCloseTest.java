package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http2.stream.Http2StreamState;
import org.junit.jupiter.api.Test;

class Http2HalfCloseTest {
  @Test
  void remoteMayCloseBeforeLocalResponseCompletes() {
    Http2StreamState state = Http2StreamState.IDLE;
    state = state.transition(1, Http2StreamState.Event.RECV_HEADERS_ES);
    assertEquals(Http2StreamState.HALF_CLOSED_REMOTE, state);
    state = state.transition(1, Http2StreamState.Event.SEND_HEADERS);
    state = state.transition(1, Http2StreamState.Event.SEND_DATA);
    state = state.transition(1, Http2StreamState.Event.SEND_DATA_ES);
    assertEquals(Http2StreamState.CLOSED, state);
  }

  @Test
  void localMayCloseWhileRemoteBodyContinues() {
    Http2StreamState state = Http2StreamState.IDLE;
    state = state.transition(1, Http2StreamState.Event.RECV_HEADERS);
    state = state.transition(1, Http2StreamState.Event.SEND_HEADERS_ES);
    assertEquals(Http2StreamState.HALF_CLOSED_LOCAL, state);
    state = state.transition(1, Http2StreamState.Event.RECV_DATA);
    state = state.transition(1, Http2StreamState.Event.RECV_DATA_ES);
    assertEquals(Http2StreamState.CLOSED, state);
  }

  @Test
  void bothSidesRemainOpenDuringBidirectionalData() {
    Http2StreamState state = Http2StreamState.IDLE;
    state = state.transition(1, Http2StreamState.Event.RECV_HEADERS);
    state = state.transition(1, Http2StreamState.Event.SEND_HEADERS);
    state = state.transition(1, Http2StreamState.Event.RECV_DATA);
    state = state.transition(1, Http2StreamState.Event.SEND_DATA);
    assertEquals(Http2StreamState.OPEN, state);
  }

  @Test
  void trailingHeadersCanCloseRemoteAfterLocalHalfClose() {
    Http2StreamState state = Http2StreamState.IDLE;
    state = state.transition(1, Http2StreamState.Event.RECV_HEADERS);
    state = state.transition(1, Http2StreamState.Event.SEND_HEADERS_ES);
    state = state.transition(1, Http2StreamState.Event.RECV_DATA);
    state = state.transition(1, Http2StreamState.Event.RECV_HEADERS_ES);
    assertEquals(Http2StreamState.CLOSED, state);
  }
}
