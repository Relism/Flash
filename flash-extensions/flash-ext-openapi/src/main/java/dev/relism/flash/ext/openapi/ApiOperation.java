package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OpenAPI operation metadata for a handler class, read by {@link OpenApiModule}.
 *
 * <pre>{@code
 * @GET("/api/blogs")
 * @ApiOperation(summary = "List all blogs", tags = {"blogs"})
 * public class ListBlogs extends JsonHandler<Void, List<Blog>> { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiOperation {
    String summary() default "";
    String description() default "";
    String[] tags() default {};
    boolean deprecated() default false;
    String operationId() default "";
}
