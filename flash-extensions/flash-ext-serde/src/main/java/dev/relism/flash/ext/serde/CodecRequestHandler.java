package dev.relism.flash.ext.serde;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import java.lang.reflect.Type;

/** A request handler whose input and output formats are fixed by its signature. */
public abstract class CodecRequestHandler<CI extends Codec, I, CO extends Codec, O>
        extends RequestHandler {
    private InputBinder.Reader input;
    private Codec writer;

    @Override
    protected final void onInit() {
        CodecBinding binding = CodecBinding.resolve(services(), getClass(), CodecRequestHandler.class);
        input = binding.input(services(), getClass());
        writer = passThrough(binding.outType) ? null : binding.out;
        if (writer != null) writer.encoder(binding.outType);
        onCodecInit();
    }

    protected void onCodecInit() {}

    protected abstract O handle(Request request, Response response, I input) throws Exception;

    @Override
    protected final Object handle(Request request, Response response) throws Exception {
        @SuppressWarnings("unchecked")
        I value = input == null ? null : (I) input.read(request);
        O answer = handle(request, response, value);
        return writer == null || answer == null ? answer : writer.write(response, answer);
    }

    private static boolean passThrough(Type type) {
        return type == Void.class || type == byte[].class || type instanceof Class<?> raw
                && (Response.class.isAssignableFrom(raw) || CharSequence.class.isAssignableFrom(raw));
    }
}
