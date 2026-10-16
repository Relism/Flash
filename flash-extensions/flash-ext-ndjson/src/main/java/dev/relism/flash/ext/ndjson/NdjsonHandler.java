package dev.relism.flash.ext.ndjson;

import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.ext.serde.TypeArguments;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import java.lang.reflect.Type;
import java.util.Locale;

/**
 * A stream of JSON values, one per line, in and out. {@code I} is what each request line is read
 * as, {@code O} what each response line is written from; {@link Void} on either side means none.
 *
 * <pre>{@code
 * @POST("/import")
 * final class Import extends NdjsonHandler<Row, Result> {
 *     @Override protected void handle(Request req, NdjsonInput<Row> in, NdjsonOutput<Result> out) throws Exception {
 *         for (Row row; (row = in.next()) != null; ) out.send(rows.save(row));
 *     }
 * }
 * }</pre>
 *
 * <p>Lines are written as they are sent and leave in batches; {@link NdjsonOutput#flush()} sends
 * what is pending, for a live feed. Until the first bytes leave, a failure is an ordinary error
 * answer; after that the response is cut off.
 */
public abstract class NdjsonHandler<I, O> extends DuplexHandler {

    private Decoder decoder;
    private Encoder encoder;

    protected abstract void handle(Request request, NdjsonInput<I> input, NdjsonOutput<O> output) throws Exception;

    @Override
    protected final void onInit() {
        String name = getClass().getSimpleName();
        Type[] types = TypeArguments.of(getClass(), NdjsonHandler.class);
        if (TypeArguments.open(types[0]) || TypeArguments.open(types[1]))
            throw new IllegalStateException(name + " leaves its NdjsonHandler types open");
        Codec codec = services().find(NdjsonModule.class).orElseThrow(() -> new IllegalStateException(
                name + " is an NdjsonHandler: install new NdjsonModule(Json.class) with the app's JSON codec")).codec(services());
        decoder = types[0] == Void.class ? null : codec.decoder(types[0]);
        encoder = types[1] == Void.class ? null : codec.encoder(types[1]);
    }

    @Override
    protected final void prepare(Request request, Response response) {
        if (decoder != null && !ndjson(request.header("content-type")))
            throw new HttpException(415, "NDJSON carries application/x-ndjson or application/jsonl");
        if (encoder != null) response.type(ContentType.NDJSON);
    }

    @Override
    protected final void handle(Request request, Response response, Duplex duplex) throws Exception {
        NdjsonOutput<O> output = new NdjsonOutput<>(duplex, encoder, getClass());
        handle(request, new NdjsonInput<>(duplex, decoder), output);
        output.drain();
    }

    private static boolean ndjson(String type) {
        if (type == null) return false;
        int parameters = type.indexOf(';');
        String media = (parameters < 0 ? type : type.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        return media.equals("application/x-ndjson") || media.equals("application/jsonl");
    }
}
