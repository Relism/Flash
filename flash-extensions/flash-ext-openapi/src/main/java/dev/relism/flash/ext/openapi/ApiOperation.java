package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares OpenAPI operation metadata for a class-based handler.
 * Picked up by {@link OpenApiExtension} via {@link FlashApp#register}.
 *
 * <pre>{@code
 * @GET("/api/blogs")
 * @ApiOperation(summary = "List all blogs", tags = {"blogs"})
 * public class ListBlogs extends JacksonHandler { ... }
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
