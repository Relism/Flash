package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
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
 * <p>A handler takes it as a constructor parameter and calls it on the hot path: the underlying
 * {@link ObjectMapper} is thread-safe once configured.
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

    /**
     * Reads the request body as {@code type} and verifies its rules.
     *
     * <p>Read straight off the request's stream, which Flash reuses per connection: nothing buffers
     * the body to hand it over. The type may be generic, and what a rule means is the installed
     * validation engine's to say — a malformed body is a 400, a body that breaks a rule is a 422,
     * and neither reaches the handler.
     *
     * @throws HttpException 400 if the body cannot be parsed as {@code type}
     */
    @Override
    public <T> T read(Request request, Type type) throws Exception {
        JavaType target = mapper.constructType(type);
        T value;
        try {
            value = mapper.readValue(request.body().stream(), target);
        } catch (JsonProcessingException malformed) {
            throw HttpException.badRequest("Invalid request body: " + malformed.getOriginalMessage());
        }
        if (validation != null) validation.verify(value);
        return value;
    }

    /** Serializes {@code value} into the one array the response sends, and sets this codec's content type. */
    @Override
    public byte[] write(Response response, Object value) throws Exception {
        response.type(contentType);
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public ContentType contentType() {
        return contentType;
    }

    /** The mapper itself, for everything these methods do not cover. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /** Takes the app's validation engine at start, and says so once when there is none. */
    @Override
    public void bind(Services services) {
        validation = services.find(Validation.class).orElse(null);
        if (validation == null) log.info("No validation engine installed: {} bodies are parsed and not checked", contentType);
    }
}
