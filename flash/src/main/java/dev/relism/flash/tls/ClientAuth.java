package dev.relism.flash.tls;

/**
 * Client-certificate requirement for a TLS listener, applied via
 * {@link TlsConfig#clientAuth(ClientAuth)}.
 */
public enum ClientAuth {
    /** No client certificate requested. Default — Flash makes no client-auth call at all. */
    NONE,
    /** Client certificate requested; handshake still succeeds if the client presents none. */
    OPTIONAL,
    /** Handshake fails unless the client presents a certificate trusted by this listener. */
    REQUIRE
}
