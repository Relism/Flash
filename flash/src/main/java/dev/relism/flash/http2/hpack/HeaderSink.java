package dev.relism.flash.http2.hpack;

import dev.relism.fpr.core.ByteView;

/** Receives decoded HPACK fields in wire order. */
@FunctionalInterface
public interface HeaderSink {
  /**
   * Accepts one field. The views are valid only for the duration of this call; a sink that needs
   * them afterwards must copy them into storage owned by the stream.
   */
  void accept(ByteView name, ByteView value, boolean neverIndexed);
}
