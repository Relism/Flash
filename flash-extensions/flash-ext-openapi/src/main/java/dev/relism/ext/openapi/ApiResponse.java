package dev.relism.ext.openapi;

import java.lang.annotation.*;

/**
 * Declares a single response for an operation. Repeatable — use multiple
 * {@code @ApiResponse} annotations on the same handler to document several status codes.
 *
 * <pre>{@code
 * @ApiResponse(status = 200, description = "Blog created", schema = Blog.class)
 * @ApiResponse(status = 400, description = "Invalid input")
 * @ApiResponse(status = 409, description = "Slug already exists")
 * public class CreateBlog extends JacksonHandler { ... }
 * }</pre>
 */
@Repeatable(ApiResponses.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiResponse {
    int    status();
    String description() default "";
    Class<?> schema()    default Void.class;
}
