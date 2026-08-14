package dev.relism.flash.http2.hpack;

/**
 * Signals that a fully decoded HPACK block exceeded the configured header-list limit. The decoder
 * delays this exception until the complete block has been consumed so dynamic-table state remains
 * synchronized with the peer. The stream layer maps it to a request rejection without closing the
 * HTTP/2 connection.
 */
public final class HeaderListSizeException extends RuntimeException {
  private final long decodedSize;

  HeaderListSizeException(long decodedSize) {
    super("decoded header list exceeds limit: " + decodedSize, null, false, false);
    this.decodedSize = decodedSize;
  }

  public long decodedSize() {
    return decodedSize;
  }
}
