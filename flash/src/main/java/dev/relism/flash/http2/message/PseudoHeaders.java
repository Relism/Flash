package dev.relism.flash.http2.message;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.fpr.core.ByteView;

/** Validates request pseudo-headers and HTTP/2 field rules while extracting request metadata. */
public final class PseudoHeaders {
  private static final int METHOD = 1;
  private static final int SCHEME = 2;
  private static final int PATH = 4;
  private static final int AUTHORITY = 8;
  private static final int PROTOCOL = 16;

  private final PooledSlice name = new PooledSlice();
  private final PooledSlice value = new PooledSlice();
  private final PooledSlice method = new PooledSlice();
  private final PooledSlice scheme = new PooledSlice();
  private final PooledSlice path = new PooledSlice();
  private final PooledSlice authority = new PooledSlice();
  private final PooledSlice protocol = new PooledSlice();
  private final PooledSlice host = new PooledSlice();
  private int present;

  public void validate(HpackHeaderBlock block, int streamId) {
    present = 0;
    method.reset(null, 0, 0);
    scheme.reset(null, 0, 0);
    path.reset(null, 0, 0);
    authority.reset(null, 0, 0);
    protocol.reset(null, 0, 0);
    host.reset(null, 0, 0);
    boolean regularSeen = false;

    for (int i = 0; i < block.count(); i++) {
      block.get(i, name, value);
      if (name.length() == 0) fail(streamId, "empty field name");
      boolean pseudo = name.byteAt(0) == ':';
      if (pseudo) {
        if (regularSeen) fail(streamId, "pseudo-header after regular field");
        int bit = pseudoBit(name);
        if (bit == 0) fail(streamId, "unknown pseudo-header");
        if ((present & bit) != 0) fail(streamId, "duplicate pseudo-header");
        present |= bit;
        copySlice(bit, value);
      } else {
        regularSeen = true;
        validateRegular(name, value, streamId);
        if (equals(name, "host")) copy(value, host);
      }
    }

    if ((present & METHOD) == 0) fail(streamId, "missing :method");
    boolean connect = equals(method, "CONNECT");
    boolean extendedConnect = (present & PROTOCOL) != 0;
    if (extendedConnect) {
      if (!connect) fail(streamId, ":protocol requires CONNECT");
      int required = METHOD | SCHEME | PATH | AUTHORITY | PROTOCOL;
      if ((present & required) != required) {
        fail(streamId, "extended CONNECT missing pseudo-header");
      }
      if (path.length() == 0) fail(streamId, "empty :path");
    } else if (connect) {
      if ((present & AUTHORITY) == 0) fail(streamId, "CONNECT requires :authority");
      if ((present & (SCHEME | PATH)) != 0) fail(streamId, "CONNECT forbids :scheme and :path");
    } else {
      int required = METHOD | SCHEME | PATH | AUTHORITY;
      if ((present & required) != required) fail(streamId, "missing request pseudo-header");
      if (path.length() == 0) fail(streamId, "empty :path");
    }
    if (host.array() != null && authority.array() != null && !equals(host, authority)) {
      fail(streamId, "host conflicts with :authority");
    }
  }

  /** Validates a trailing field section, where pseudo-fields are never permitted. */
  public static void validateTrailers(HpackHeaderBlock block, int streamId) {
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    for (int i = 0; i < block.count(); i++) {
      block.get(i, name, value);
      if (name.length() == 0 || name.byteAt(0) == ':') fail(streamId, "pseudo-header in trailers");
      validateRegular(name, value, streamId);
      if (equals(name, "content-length") || equals(name, "host") || equals(name, "te")) {
        fail(streamId, "field is not permitted in trailers");
      }
    }
  }

  public PooledSlice method() {
    return method;
  }

  public PooledSlice scheme() {
    return scheme;
  }

  public PooledSlice path() {
    return path;
  }

  public PooledSlice authority() {
    return authority;
  }

  public boolean websocket() {
    return protocol.array() != null && equals(protocol, "websocket");
  }

  private void copySlice(int bit, PooledSlice source) {
    if (bit == METHOD) copy(source, method);
    else if (bit == SCHEME) copy(source, scheme);
    else if (bit == PATH) copy(source, path);
    else if (bit == AUTHORITY) copy(source, authority);
    else copy(source, protocol);
  }

  private static void copy(PooledSlice source, PooledSlice target) {
    target.reset(source.array(), source.offset(), source.length());
  }

  private static int pseudoBit(ByteView name) {
    if (equals(name, ":method")) return METHOD;
    if (equals(name, ":scheme")) return SCHEME;
    if (equals(name, ":path")) return PATH;
    if (equals(name, ":authority")) return AUTHORITY;
    if (equals(name, ":protocol")) return PROTOCOL;
    return 0;
  }

  private static void validateRegular(ByteView name, ByteView value, int streamId) {
    for (int i = 0; i < name.length(); i++) {
      int c = name.byteAt(i) & 0xff;
      if (c >= 'A' && c <= 'Z') fail(streamId, "uppercase field name");
    }
    if (equals(name, "connection")
        || equals(name, "keep-alive")
        || equals(name, "proxy-connection")
        || equals(name, "transfer-encoding")
        || equals(name, "upgrade")) {
      fail(streamId, "connection-specific field");
    }
    if (equals(name, "te") && !equals(value, "trailers")) {
      fail(streamId, "invalid te field");
    }
  }

  static boolean equals(ByteView view, String expected) {
    if (view.length() != expected.length()) return false;
    for (int i = 0; i < view.length(); i++) {
      if ((view.byteAt(i) & 0xff) != expected.charAt(i)) return false;
    }
    return true;
  }

  static boolean equals(ByteView left, ByteView right) {
    if (left.length() != right.length()) return false;
    for (int i = 0; i < left.length(); i++) {
      if (left.byteAt(i) != right.byteAt(i)) return false;
    }
    return true;
  }

  private static void fail(int streamId, String message) {
    throw new Http2StreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, message);
  }
}
