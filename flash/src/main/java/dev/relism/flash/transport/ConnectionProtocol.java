package dev.relism.flash.transport;

import java.io.IOException;

/**
 * The h1/h2 seam R1 requires: the protocol decision is made once, immediately after
 * ALPN/preface detection ({@link ConnectionRunner}), and dispatches to one implementation of
 * this interface. After that point neither implementation knows the other exists.
 */
public interface ConnectionProtocol {

    /** Runs this connection to completion. Returns when the connection should be closed. */
    void run(ConnectionContext ctx) throws IOException;
}
