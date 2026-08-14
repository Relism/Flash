package dev.relism.flash.models;

import java.io.IOException;
import java.io.OutputStream;

/** Adapts a flow-controlled response stream to APIs that write to an {@link OutputStream}. */
public final class ResponseStreamOutputStream extends OutputStream {
  private final ResponseStream stream;
  private final byte[] single = new byte[1];

  public ResponseStreamOutputStream(ResponseStream stream) {
    this.stream = stream;
  }

  @Override
  public void write(int value) throws IOException {
    single[0] = (byte) value;
    stream.write(single, 0, 1);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    stream.write(bytes, offset, length);
  }

  @Override
  public void flush() throws IOException {
    stream.flush();
  }

  @Override
  public void close() throws IOException {
    stream.close();
  }
}
