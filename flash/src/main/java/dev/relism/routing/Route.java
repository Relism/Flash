package dev.relism.routing;

import dev.relism.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * HTTP method and path for a class-based {@link dev.relism.models.RequestHandler}.
 * Path is relative to the mount namespace (e.g. {@code path = "/profile"} under {@code /api}
 * → {@code /api/profile}).
 *
 * <p>Prefer shorthand ({@code @GET("/x")}, {@code @POST("/x")}, …); they are meta-annotated
 * with {@code @Route} and resolve to the same metadata as an explicit {@code @Route(method=…, path=…)}
 * via {@link Routes#of(Class)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface Route {
    HttpMethod method() default HttpMethod.GET;
    String path();
}
