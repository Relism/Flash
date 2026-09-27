package dev.relism.flash.http;

import java.io.IOException;

/** Blocking, flow-controlled response body used by push-style streaming producers. */
public interface ResponseStream extends AutoCloseable {
  void write(byte[] data, int offset, int length) throws IOException;
  void flush() throws IOException;
  void trailer(String name, String value);
  @Override void close() throws IOException;
}
