package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

/**
 * One Jackson mapper, in the shape a handler needs it.
 *
 * <p>Every Jackson data format is the same databind model behind a different factory, so this is
 * the whole of what a format module has to say: which mapper, and which content type it writes.
 * What the annotations mean, what a body is checked against and how a type is described are the
 * same for all of them.
 *
 * <p>Retrieve it once at boot — {@code @Inject private Json json;} — and call it on the hot path.
 * The underlying {@link ObjectMapper} is thread-safe once configured.
 */
public abstract class Codec {

    private final ObjectMapper mapper;
    private final ContentType contentType;

    protected Codec(ObjectMapper mapper, ContentType contentType) {
        this.mapper = mapper;
        this.contentType = contentType;
    }

    /**
     * Reads the request body as {@code type} and verifies its constraints.
     *
     * <p>Read straight off the request's stream, which Flash reuses per connection: nothing
     * buffers the body to hand it over. A type that declares no constraints is not checked at all.
     *
     * @throws HttpException 400 if the body cannot be parsed as {@code type}
     * @throws ValidationException 422 if it parses but violates a constraint
     */
    public <T> T body(Request request, Class<T> type) throws Exception {
        T value;
        try {
            value = mapper.readValue(request.body().stream(), type);
        } catch (JsonProcessingException malformed) {
            throw HttpException.badRequest("Invalid request body: " + malformed.getOriginalMessage());
        }
        Validator.of(type).verify(value);
        return value;
    }

    /** Serializes {@code value} and sets this codec's content type on the response. */
    public String write(Response response, Object value) throws Exception {
        response.type(contentType);
        return mapper.writeValueAsString(value);
    }

    /** Like {@link #write}, restricted to the fields visible under a Jackson {@code @JsonView}. */
    public String writeView(Response response, Object value, Class<?> view) throws Exception {
        response.type(contentType);
        return mapper.writerWithView(view).writeValueAsString(value);
    }

    /** What this codec writes, for a handler that sets the response type itself. */
    public ContentType contentType() {
        return contentType;
    }

    /** The mapper itself, for everything these methods do not cover. */
    public ObjectMapper mapper() {
        return mapper;
    }
}
