package dev.relism.flash.ext.ndjson;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.http.Duplex;
import java.io.IOException;

/**
 * The response's lines. {@link #send} encodes one value and its {@code \n} into a buffer reused for
 * the whole response, which leaves when it fills, on {@link #flush()}, and when the handler returns.
 * One thread sends at a time.
 */
public final class NdjsonOutput<T> {

    private static final int BATCH = 8 * 1024;

    private final Duplex duplex;
    private final Encoder encoder;
    private final Class<?> handler;
    private final ByteWriter out;
    private boolean broken; // a send failed halfway: the buffer ends in part of a line

    NdjsonOutput(Duplex duplex, Encoder encoder, Class<?> handler) {
        this.duplex = duplex;
        this.encoder = encoder;
        this.handler = handler;
        this.out = encoder == null ? null : new ByteWriter(BATCH);
    }

    /** Writes {@code value} as one line. */
    public void send(T value) throws IOException {
        if (encoder == null) throw new IllegalStateException(handler.getSimpleName() + " writes Void: it sends no lines");
        if (broken) throw new IOException("an earlier send failed, so the response cannot go on");
        int start = out.length();
        broken = true;
        try {
            encoder.encode(value, out);
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        byte[] bytes = out.array();
        for (int i = start; i < out.length(); i++)
            if (bytes[i] == '\n' || bytes[i] == '\r')
                throw new IllegalStateException(handler.getSimpleName() + ": a value encoded with a line break, "
                        + "which NDJSON forbids; turn off the codec's pretty printing");
        out.writeByte((byte) '\n');
        broken = false;
        if (out.length() >= BATCH) drain();
    }

    /** Sends the lines written so far. */
    public void flush() throws IOException {
        drain();
        duplex.flush();
    }

    void drain() throws IOException {
        if (out == null || out.length() == 0) return;
        if (broken) throw new IOException("an earlier send failed, so the response cannot go on");
        duplex.write(out.array(), 0, out.length());
        out.reset();
    }
}
