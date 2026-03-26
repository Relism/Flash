package dev.relism.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.exceptions.HttpException;
import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;

/**
 * Base class for handlers that need JSON I/O via Jackson.
 *
 * <p>The {@link ObjectMapper} is injected by {@link JacksonExtension#install} once at
 * startup — all subclasses share the same instance. If no {@code JacksonExtension} is
 * installed the field remains {@code null} and the first call to {@link #bodyAs} or
 * {@link #json} will throw an {@link IllegalStateException}.
 *
 * <pre>{@code
 * @Route(method = HttpMethod.POST, path = "/api/blogs")
 * public class CreateBlog extends JacksonHandler {
 *     public Object handle(Request req, Response res) throws Exception {
 *         CreateBlogRequest body = bodyAs(req, CreateBlogRequest.class);
 *         Blog created = service.create(body);
 *         res.setStatusCode(201);
 *         return json(res, created);
 *     }
 * }
 * }</pre>
 */
public abstract class JacksonHandler extends RequestHandler {

    /**
     * Shared mapper set by {@link JacksonExtension}. Package-visible so the extension
     * can assign it; {@code volatile} ensures visibility across virtual threads.
     */
    static volatile ObjectMapper mapper;

    /**
     * Deserializes the request body bytes into {@code type}.
     * Wraps Jackson parse errors as {@link HttpException} 400.
     */
    protected <T> T bodyAs(Request req, Class<T> type) throws Exception {
        requireMapper();
        try {
            return mapper.readValue(req.body().bytes(), type);
        } catch (JsonProcessingException e) {
            throw HttpException.badRequest("Invalid request body: " + e.getOriginalMessage());
        }
    }

    /**
     * Serializes {@code obj} to JSON, sets {@code Content-Type: application/json},
     * and returns the JSON string as the response body.
     */
    protected String json(Response res, Object obj) throws Exception {
        requireMapper();
        res.setContentType(ContentType.JSON);
        return mapper.writeValueAsString(obj);
    }

    /**
     * Like {@link #json} but serializes only fields visible under the given
     * {@code view} class (see Jackson {@code @JsonView}).
     */
    protected String jsonView(Response res, Object obj, Class<?> view) throws Exception {
        requireMapper();
        res.setContentType(ContentType.JSON);
        return mapper.writerWithView(view).writeValueAsString(obj);
    }

    private static void requireMapper() {
        if (mapper == null)
            throw new IllegalStateException(
                    "JacksonExtension not installed: call FlashApp.install(new JacksonExtension()) first");
    }
}
