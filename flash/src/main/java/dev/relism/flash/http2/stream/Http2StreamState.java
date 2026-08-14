package dev.relism.flash.http2.stream;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2StreamException;

/** Explicit RFC 9113 stream-state transition table. */
public enum Http2StreamState {
  IDLE,
  OPEN,
  HALF_CLOSED_REMOTE,
  HALF_CLOSED_LOCAL,
  CLOSED;

  public enum Event {
    RECV_HEADERS,
    RECV_HEADERS_ES,
    RECV_DATA,
    RECV_DATA_ES,
    RECV_RST,
    SEND_HEADERS,
    SEND_HEADERS_ES,
    SEND_DATA,
    SEND_DATA_ES,
    SEND_RST
  }

  private static final byte ERROR = -1;
  private static final byte[][] TRANSITIONS = buildTransitions();
  // enum values() clones its backing array on every call; this is read-only and shared safely.
  private static final Http2StreamState[] VALUES = values();

  public Http2StreamState transition(int streamId, Event event) {
    int next = TRANSITIONS[ordinal()][event.ordinal()];
    if (next == ERROR) {
      throw new Http2StreamException(
          streamId, errorFor(event), "invalid stream transition " + this + " + " + event);
    }
    return VALUES[next];
  }

  private Http2ErrorCode errorFor(Event event) {
    if (this == CLOSED) return Http2ErrorCode.STREAM_CLOSED;
    if (this == HALF_CLOSED_REMOTE
        && (event == Event.RECV_DATA
            || event == Event.RECV_DATA_ES
            || event == Event.RECV_HEADERS
            || event == Event.RECV_HEADERS_ES)) {
      return Http2ErrorCode.STREAM_CLOSED;
    }
    return Http2ErrorCode.PROTOCOL_ERROR;
  }

  public static boolean isValid(Http2StreamState state, Event event) {
    return TRANSITIONS[state.ordinal()][event.ordinal()] != ERROR;
  }

  private static byte[][] buildTransitions() {
    byte[][] table = new byte[values().length][Event.values().length];
    for (byte[] row : table) java.util.Arrays.fill(row, ERROR);

    set(table, IDLE, Event.RECV_HEADERS, OPEN);
    set(table, IDLE, Event.RECV_HEADERS_ES, HALF_CLOSED_REMOTE);

    set(table, OPEN, Event.RECV_HEADERS, OPEN);
    set(table, OPEN, Event.RECV_HEADERS_ES, HALF_CLOSED_REMOTE);
    set(table, OPEN, Event.RECV_DATA, OPEN);
    set(table, OPEN, Event.RECV_DATA_ES, HALF_CLOSED_REMOTE);
    set(table, OPEN, Event.RECV_RST, CLOSED);
    set(table, OPEN, Event.SEND_HEADERS, OPEN);
    set(table, OPEN, Event.SEND_HEADERS_ES, HALF_CLOSED_LOCAL);
    set(table, OPEN, Event.SEND_DATA, OPEN);
    set(table, OPEN, Event.SEND_DATA_ES, HALF_CLOSED_LOCAL);
    set(table, OPEN, Event.SEND_RST, CLOSED);

    set(table, HALF_CLOSED_REMOTE, Event.RECV_RST, CLOSED);
    set(table, HALF_CLOSED_REMOTE, Event.SEND_HEADERS, HALF_CLOSED_REMOTE);
    set(table, HALF_CLOSED_REMOTE, Event.SEND_HEADERS_ES, CLOSED);
    set(table, HALF_CLOSED_REMOTE, Event.SEND_DATA, HALF_CLOSED_REMOTE);
    set(table, HALF_CLOSED_REMOTE, Event.SEND_DATA_ES, CLOSED);
    set(table, HALF_CLOSED_REMOTE, Event.SEND_RST, CLOSED);

    set(table, HALF_CLOSED_LOCAL, Event.RECV_HEADERS, HALF_CLOSED_LOCAL);
    set(table, HALF_CLOSED_LOCAL, Event.RECV_HEADERS_ES, CLOSED);
    set(table, HALF_CLOSED_LOCAL, Event.RECV_DATA, HALF_CLOSED_LOCAL);
    set(table, HALF_CLOSED_LOCAL, Event.RECV_DATA_ES, CLOSED);
    set(table, HALF_CLOSED_LOCAL, Event.RECV_RST, CLOSED);
    set(table, HALF_CLOSED_LOCAL, Event.SEND_RST, CLOSED);

    set(table, CLOSED, Event.RECV_RST, CLOSED);
    set(table, CLOSED, Event.SEND_RST, CLOSED);
    return table;
  }

  private static void set(byte[][] table, Http2StreamState from, Event event, Http2StreamState to) {
    table[from.ordinal()][event.ordinal()] = (byte) to.ordinal();
  }
}
