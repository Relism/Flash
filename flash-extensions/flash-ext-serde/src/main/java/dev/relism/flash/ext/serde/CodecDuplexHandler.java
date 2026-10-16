package dev.relism.flash.ext.serde;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.DuplexHandler;

/** Serde binding for protocol-specific duplex handlers; it defines no framing or cardinality. */
public abstract class CodecDuplexHandler<CI extends Codec, I, CO extends Codec, O>
        extends DuplexHandler {
    private Decoder decoder;
    private Encoder encoder;

    @Override
    protected final void onInit() {
        CodecBinding binding = CodecBinding.resolve(services(), getClass(), CodecDuplexHandler.class);
        decoder = binding.inType == Void.class ? null : binding.in.decoder(binding.inType);
        encoder = binding.outType == Void.class ? null : binding.out.encoder(binding.outType);
        onCodecInit();
    }

    protected void onCodecInit() {}

    @SuppressWarnings("unchecked")
    protected final I decode(byte[] bytes, int offset, int length) throws Exception {
        if (decoder == null) throw new IllegalStateException("this handler has no input codec");
        return (I) decoder.decode(bytes, offset, length);
    }

    protected final void encode(O value, ByteWriter output) throws Exception {
        if (encoder == null) throw new IllegalStateException("this handler has no output codec");
        encoder.encode(value, output);
    }
}
