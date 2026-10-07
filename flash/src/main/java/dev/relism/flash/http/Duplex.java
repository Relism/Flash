package dev.relism.flash.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An exchange that stays open both ways, as bytes: the request body still arriving, the response
 * body written as it is produced. A stream, an event feed, a tunnel, a protocol of messages.
 * The status and headers leave with its first {@code write} or {@code flush}, the response's
 * trailers when its exchange returns.
 *
 * <p>Reading is in place: {@link #fill} makes bytes available in {@link #array()} from
 * {@link #offset()}, and {@link #advance} consumes them. Writes block while the peer cannot take
 * more. One thread reads and one writes at a time; they may be different threads.
 */
public abstract class Duplex extends OutputStream {

    private Response response;
    private boolean switchProtocol;

    private final byte[] one = new byte[1];

    @Override
    public final void write(int b) throws IOException {
        one[0] = (byte) b;
        write(one, 0, 1);
    }

    @Override
    public abstract void write(byte[] b, int off, int len) throws IOException;

    /** Binds the response plan immediately before delayed execution. */
    public final void bind(Response response, boolean switchProtocol) {
        this.response = response;
        this.switchProtocol = switchProtocol;
    }

    protected final Response response() {
        return response;
    }

    protected final boolean switchesProtocol() {
        return switchProtocol;
    }

    /** Sends what was written so far, the status and headers too when nothing has gone yet. */
    @Override
    public abstract void flush() throws IOException;

    /**
     * Blocks until request bytes are available and returns how many, from {@link #offset()} in
     * {@link #array()}; -1 once the peer has finished sending. Bytes not yet advanced over are
     * returned again, without waiting.
     */
    public abstract int fill() throws IOException;

    /** The bytes {@link #fill} made available; valid until the next {@code fill} or {@code advance}. */
    public abstract byte[] array();

    public abstract int offset();

    /** Consumes {@code n} of the bytes {@link #fill} made available. */
    public abstract void advance(int n);
}
