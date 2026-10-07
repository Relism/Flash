package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;

/**
 * One Jackson mapper, in the shape a handler needs it.
 *
 * <p>Every Jackson data format is the same databind model behind a different factory, so this is
 * the whole of what a format module has to say: which mapper, and which content type it writes.
 * What the annotations mean and how a type is described are the same for all of them.
 *
 * <p>A request body is parsed as it arrives, never held whole; a message is parsed where the
 * protocol holds it. The readers and writers are resolved once per type, when a handler asks at
 * boot, and the underlying {@link ObjectMapper} is thread-safe once configured.
 */
public abstract class JacksonCodec implements Codec, Bound {

    private static final Logger log = LoggerFactory.getLogger(JacksonCodec.class);

    private final ObjectMapper mapper;
    private final ContentType contentType;

    /** The engine that checks what is read, or null when none is installed; written once at boot. */
    private Validation validation;

    protected JacksonCodec(ObjectMapper mapper, ContentType contentType) {
        this.mapper = mapper;
        this.contentType = contentType;
    }

    @Override
    public Decoder decoder(Type type) {
        ObjectReader reader = mapper.readerFor(mapper.constructType(type));
        return new Decoder() {
            @Override
            public Object decode(byte[] bytes, int offset, int length) throws Exception {
                try {
                    return checked(reader.readValue(bytes, offset, length));
                } catch (JsonProcessingException malformed) {
                    throw HttpException.badRequest("Invalid message: " + malformed.getOriginalMessage());
                }
            }

            @Override
            public Object decode(dev.relism.flash.http.Request request) throws Exception {
                try {
                    return checked(reader.readValue(request.body().stream()));
                } catch (JsonProcessingException malformed) {
                    throw HttpException.badRequest("Invalid request body: " + malformed.getOriginalMessage());
                }
            }
        };
    }

    @Override
    public Encoder encoder(Type type) {
        ObjectWriter writer = mapper.writerFor(mapper.constructType(type));
        return new Encoder() {
            @Override
            public void encode(Object value, ByteWriter out) throws Exception {
                writer.writeValue(out, value);
            }

            @Override
            public ContentType type() {
                return contentType;
            }
        };
    }

    @Override
    public byte[] write(Response response, Object value) throws Exception {
        response.type(contentType);
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public ContentType contentType() {
        return contentType;
    }

    /** The mapper itself, for what a handler does with it beyond reading and writing a body. */
    public ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public void bind(Services services) {
        validation = services.find(Validation.class).orElse(null);
        if (validation == null) log.info("No validation engine installed: {} bodies are parsed and not checked", contentType);
    }

    private Object checked(Object value) {
        if (validation != null) validation.verify(value);
        return value;
    }
}
