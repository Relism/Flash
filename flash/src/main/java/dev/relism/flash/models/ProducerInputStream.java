package dev.relism.flash.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.function.Consumer;

/** Bounded bridge from a push producer to the protocol writers' common pull path. */
final class ProducerInputStream extends InputStream {
  private final PipedInputStream input;
  private final ProducerOutput output;
  private final Consumer<ResponseStream> producer;
  private volatile Throwable failure;
  private boolean started;

  ProducerInputStream(Consumer<ResponseStream> producer, Response response) {
    try {
      input = new PipedInputStream(16 * 1024);
      output = new ProducerOutput(new PipedOutputStream(input), response);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    this.producer = producer;
  }

  @Override
  public int read() throws IOException {
    start();
    int value = input.read();
    checkFailure(value < 0);
    return value;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    start();
    int count = input.read(bytes, offset, length);
    checkFailure(count < 0);
    return count;
  }

  @Override
  public void close() throws IOException {
    input.close();
  }

  private synchronized void start() {
    if (started) return;
    started = true;
    Thread.startVirtualThread(() -> {
      try (output) {
        producer.accept(output);
      } catch (Throwable thrown) {
        failure = thrown;
        try {
          output.close();
        } catch (IOException ignored) {
        }
      }
    });
  }

  private void checkFailure(boolean eof) throws IOException {
    if (eof && failure != null) throw new IOException("response stream producer failed", failure);
  }

  private static final class ProducerOutput implements ResponseStream {
    private final PipedOutputStream output;
    private final Response response;
    private boolean closed;

    ProducerOutput(PipedOutputStream output, Response response) {
      this.output = output;
      this.response = response;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
      if (closed) throw new IOException("response stream is closed");
      output.write(data, offset, length);
    }

    @Override
    public synchronized void flush() throws IOException {
      if (closed) throw new IOException("response stream is closed");
      output.flush();
    }

    @Override
    public synchronized void trailer(String name, String value) {
      if (closed) throw new IllegalStateException("response stream is closed");
      response.trailer(name, value);
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      output.close();
    }
  }
}
