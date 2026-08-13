package dev.relism.flash.models;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A header name/value pair pre-encoded once (typically at boot, as a {@code static final}
 * constant) and reused across many responses via {@link Response#header(PreEncodedHeader)}.
 *
 * The older {@code header(byte[])} overload takes an already-fully-rendered h1 field line
 * (e.g. {@code "X-RateLimit-Limit: 100\r\n"}) — fine for h1, but not valid HPACK: HPACK encodes
 * a header as a compressed (name, value) pair, never as a literal CRLF-terminated line, so a
 * pre-rendered h1 line carries no information an HPACK encoder could reuse. {@code
 * PreEncodedHeader} instead precomputes the {@code name}/{@code value} bytes <em>separately</em>
 * (still once, still at boot) so either protocol's writer can render them in its own format —
 * {@link Response#header(byte[])} is kept, working, for h1-only callers, but is documented as
 * ignored on a future HTTP/2 response path (there is no way to recover structured name/value data
 * from an opaque pre-rendered line); prefer this class for any header a handler wants to send on
 * both protocols.
 *
 * class stores the raw {@code name}/{@code value} bytes now, which is everything a future HPACK
 * encoder needs to produce its own rendering from; it does not yet expose a precomputed HPACK
 * byte form, since building one before HPACK exists would be speculative, untested API surface.
 */
public final class PreEncodedHeader {
    private final byte[] nameBytes;
    private final byte[] valueBytes;

    public PreEncodedHeader(String name, String value) {
        this.nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        this.valueBytes = value.getBytes(StandardCharsets.US_ASCII);
    }

    /** The header name's ASCII bytes, case as given to the constructor. Never copy-on-read — treat as immutable. */
    byte[] nameBytes() {
        return nameBytes;
    }

    /** The header value's ASCII bytes. Never copy-on-read — treat as immutable. */
    byte[] valueBytes() {
        return valueBytes;
    }

    @Override
    public String toString() {
        return new String(nameBytes, StandardCharsets.US_ASCII) + ": " + new String(valueBytes, StandardCharsets.US_ASCII);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PreEncodedHeader other)) return false;
        return Arrays.equals(nameBytes, other.nameBytes) && Arrays.equals(valueBytes, other.valueBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(nameBytes) + Arrays.hashCode(valueBytes);
    }
}
