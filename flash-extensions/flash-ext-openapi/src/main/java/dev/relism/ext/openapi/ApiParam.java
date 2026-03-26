package dev.relism.ext.openapi;

import java.lang.annotation.*;

/**
 * Declares a single parameter (query, path, header, or cookie) for an operation.
 * Repeatable — place multiple annotations on the same handler class.
 *
 * <pre>{@code
 * @ApiParam(name = "limit",  in = "query", type = "integer", description = "Max results (default 20)")
 * @ApiParam(name = "offset", in = "query", type = "integer", description = "Pagination offset")
 * public class ListBlogs extends JacksonHandler { ... }
 * }</pre>
 */
@Repeatable(ApiParams.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiParam {
    String name();
    /** "query", "path", "header", or "cookie". */
    String in()          default "query";
    /** OpenAPI primitive type: "string", "integer", "number", "boolean". */
    String type()        default "string";
    String description() default "";
    boolean required()   default false;
    String example()     default "";
}
