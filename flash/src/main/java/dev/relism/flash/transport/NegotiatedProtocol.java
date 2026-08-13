package dev.relism.flash.transport;

/**
 * The result of {@link ProtocolNegotiator#negotiate}: which protocol a connection will speak,
 * decided once, immediately after ALPN or the h2c preface is inspected, per R1.
 */
public enum NegotiatedProtocol {
    HTTP_1_1,
    H2
}
