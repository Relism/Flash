package dev.relism.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.exceptions.HttpException;
import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.Response;

/**
 * Thread-safe JSON toolbox. Single point of access for all JSON I/O operations
 * within a Flash application.
 *
 * <p>Retrieve once at boot time via {@code require(Json.class)} inside
 * {@code onInit()}, cache in a private field, and call on the hot path
 * with zero lookup or allocation overhead:
 *
 * <pre>{@code
 * @Route(method = HttpMethod.POST, path = "/api/items")
 * public class CreateItemHandler extends RequestHandler {
 *
 *     private Json json;
 *
 *     @Override
 *     protected void onInit() {
 *         json = require(Json.class);
 *     }
 *
 *     public Object handle(Request req, Response res) throws Exception {
 *         CreateItemRequest body = json.body(req, CreateItemRequest.class);
 *         return json.write(res, itemService.create(body));
 *     }
 * }
 * }</pre>
 *
 * <p>The underlying {@link ObjectMapper} is shared across all handlers in the same
 * scope (one instance per app / per child scope). Jackson's {@code ObjectMapper}
 * is fully thread-safe after configuration — no synchronization is needed.
 *
 * <p>Install via {@link JacksonExtension} before calling {@code scan()} or
 * {@code register()}.
 */
public final class Json {

    private final ObjectMapper mapper;

    /** Package-private — constructed exclusively by {@link JacksonExtension}. */
    Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    /**
     * Deserializes the full request body into an instance of {@code type}.
     *
     * <p>Reads {@code req.body().bytes()} in one shot. For streaming bodies
     * use {@link #bodyFrom(Request, Class)} instead.
     *
     * @throws HttpException 400 if the body cannot be parsed as {@code type}
     */
    public <T> T body(Request req, Class<T> type) throws Exception {
        try {
            return mapper.readValue(req.body().bytes(), type);
        } catch (JsonProcessingException e) {
            throw HttpException.badRequest("Invalid request body: " + e.getOriginalMessage());
        }
    }

    /**
     * Deserializes the request body via the raw {@link java.io.InputStream},
     * avoiding the intermediate {@code byte[]} allocation. Prefer this for
     * large bodies or when allocation budget is tight.
     *
     * @throws HttpException 400 on parse failure
     */
    public <T> T bodyFrom(Request req, Class<T> type) throws Exception {
        try {
            return mapper.readValue(req.body().stream(), type);
        } catch (JsonProcessingException e) {
            throw HttpException.badRequest("Invalid request body: " + e.getOriginalMessage());
        }
    }

    // ── Output ────────────────────────────────────────────────────────────────

    /**
     * Serializes {@code obj} to a JSON string and sets
     * {@code Content-Type: application/json} on the response.
     *
     * <p>The returned string is used as the response body by the Flash runtime.
     */
    public String write(Response res, Object obj) throws Exception {
        res.type(ContentType.JSON);
        return mapper.writeValueAsString(obj);
    }

    /**
     * Like {@link #write} but applies a Jackson {@code @JsonView} filter,
     * restricting serialization to fields visible under {@code view}.
     */
    public String writeView(Response res, Object obj, Class<?> view) throws Exception {
        res.type(ContentType.JSON);
        return mapper.writerWithView(view).writeValueAsString(obj);
    }

    // ── Escape hatch ──────────────────────────────────────────────────────────

    /**
     * Returns the underlying {@link ObjectMapper} for advanced operations
     * (custom serialization, schema generation, etc.) not covered by the
     * methods above.
     */
    public ObjectMapper mapper() {
        return mapper;
    }
}
