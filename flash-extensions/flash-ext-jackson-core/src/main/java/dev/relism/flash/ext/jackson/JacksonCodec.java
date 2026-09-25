package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * One Jackson mapper, in the shape a handler needs it.
 *
 * <p>Every Jackson data format is the same databind model behind a different factory, so this is
 * the whole of what a format module has to say: which mapper, and which content type it writes.
 * What the annotations mean and how a type is described are the same for all of them.
 *
 * <p>Retrieve it once at boot — {@code @Inject private Json json;} — and call it on the hot path.
 * The underlying {@link ObjectMapper} is thread-safe once configured.
 */
@Slf4j
public abstract class JacksonCodec implements Codec {

    private final ObjectMapper mapper;
    private final ContentType contentType;

    /**
     * The engine that checks what is read, or null when no application installed one.
     *
     * <p>Written once from a ready callback, which runs while the application boots and long
     * before a request thread exists: the thread that serves a request starts after that write and
     * therefore sees it.
     */
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

    /** Serializes {@code value} and sets this codec's content type on the response. */
    @Override
    public String write(Response response, Object value) throws Exception {
        response.type(contentType);
        return mapper.writeValueAsString(value);
    }

    @Override
    public ContentType contentType() {
        return contentType;
    }

    /** The mapper itself, for everything these methods do not cover. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Binds the installed validation engine at boot, and says so when there is none: a body that
     * declares rules nobody checks is worth one line in the log.
     *
     * <p>Called by the format's own extension, from {@code configure}.
     */
    public void bindValidation(FlashContext ctx) {
        ctx.onReady(() -> {
            Optional<Validation> engine = ctx.find(Validation.class);
            engine.ifPresent(found -> validation = found);
            if (engine.isEmpty()) {
                log.info("No validation engine installed: {} bodies are parsed and not checked", contentType);
            }
        });
    }
}
