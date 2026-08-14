package dev.relism.flash.transport;

/**
 * The result of {@link ProtocolNegotiator#negotiate}: which protocol a connection will speak,
 */
public enum NegotiatedProtocol {
    HTTP_1_1,
    HTTP_2
}
