package dev.relism.routing;

import dev.relism.http.HttpMethod;

import java.lang.annotation.Annotation;

/** Boot-time resolution of {@link Route} from a handler class (direct {@code @Route} or shorthand). */
public final class Routes {
    private Routes() {}

    /**
     * Effective route for {@code cls}, or {@code null}. Prefers {@code @Route} on the class;
     * otherwise the first annotation type meta-annotated with {@code @Route} (e.g. {@code @GET}).
     * If several routing annotations are present, behaviour is undefined — use one.
     */
    public static Route of(Class<?> cls) {
        Route direct = cls.getAnnotation(Route.class);
        if (direct != null) return direct;
        for (Annotation ann : cls.getAnnotations()) {
            Route meta = ann.annotationType().getAnnotation(Route.class);
            if (meta == null) continue;
            try {
                String path = (String) ann.annotationType().getMethod("value").invoke(ann);
                final HttpMethod method = meta.method();
                return new Route() {
                    public HttpMethod method()                           { return method; }
                    public String path()                                { return path;   }
                    public Class<? extends Annotation> annotationType() { return Route.class; }
                };
            } catch (ReflectiveOperationException ignored) {}
        }
        return null;
    }
}
