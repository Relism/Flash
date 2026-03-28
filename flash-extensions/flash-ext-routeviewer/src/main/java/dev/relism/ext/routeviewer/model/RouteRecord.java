package dev.relism.ext.routeviewer.model;

import dev.relism.extension.RouteEvent;
import dev.relism.routing.Middleware;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Enriched snapshot of a single registered route.
 *
 * <p>Built once at registration time from a {@link RouteEvent}.
 * All expensive operations (superclass traversal, annotation reading)
 * happen here — never on the request hot-path.
 *
 * @param event            the raw event emitted by the Flash core
 * @param abstractionChain handler class hierarchy, outermost first, stopping before
 *                         {@code RequestHandler} (e.g. {@code ["EditPostPageHandler", "HtmlHandler"]}).
 *                         Empty for anonymous lambda handlers.
 * @param pointcuts        semantic annotations declared on the handler class hierarchy,
 *                         used as declarative pointcut descriptors
 * @param middlewareNames  cleaned simple class names of the middleware chain, outermost first
 */
public record RouteRecord(
        RouteEvent       event,
        List<String>     abstractionChain,
        List<String>     pointcuts,
        List<String>     middlewareNames
) {

    /** The root class we stop at (exclusive) — always implied, never shown. */
    private static final String ROOT_HANDLER = "RequestHandler";

    /** Builds a {@code RouteRecord} from a raw {@link RouteEvent}. */
    public static RouteRecord from(RouteEvent event) {
        return new RouteRecord(
                event,
                buildAbstractionChain(event.handlerClass()),
                buildPointcuts(event.handlerClass()),
                buildMiddlewareNames(event.middlewareChain())
        );
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    /**
     * Walks the superclass chain, stopping before {@code RequestHandler}.
     * {@code RequestHandler} is the universal root — showing it adds no information.
     */
    private static List<String> buildAbstractionChain(Class<?> cls) {
        if (cls == null) return List.of();
        List<String> chain = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && !c.equals(Object.class)) {
            if (ROOT_HANDLER.equals(c.getSimpleName())) break;
            chain.add(c.getSimpleName());
            c = c.getSuperclass();
        }
        return List.copyOf(chain);
    }

    private static List<String> buildPointcuts(Class<?> cls) {
        if (cls == null) return List.of();
        List<String> pointcuts = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && !c.equals(Object.class)) {
            if (ROOT_HANDLER.equals(c.getSimpleName())) break;
            for (Annotation ann : c.getDeclaredAnnotations()) {
                String name = ann.annotationType().getSimpleName();
                if (!name.equals("Route") && !name.equals("Override"))
                    pointcuts.add(formatAnnotation(ann));
            }
            c = c.getSuperclass();
        }
        return List.copyOf(pointcuts);
    }

    /**
     * Strips the synthetic lambda suffix ({@code $$Lambda/0x...}) from class names
     * so that {@code OidcMiddleware$$Lambda/0x0000019c381f} becomes {@code OidcMiddleware}.
     */
    private static List<String> buildMiddlewareNames(List<Class<? extends Middleware>> chain) {
        List<String> names = new ArrayList<>(chain.size());
        for (Class<? extends Middleware> cls : chain) names.add(cleanName(cls.getSimpleName()));
        return List.copyOf(names);
    }

    private static String cleanName(String simpleName) {
        int dollar = simpleName.indexOf("$$");
        return dollar >= 0 ? simpleName.substring(0, dollar) : simpleName;
    }

    /**
     * Formats an annotation for display.
     * <ul>
     *   <li>Marker annotations → {@code @Name}</li>
     *   <li>{@code String} value → {@code @Name(value)}</li>
     *   <li>{@code String[]} value → {@code @Name(a, b)}</li>
     *   <li>Any other value type (annotation arrays, class refs, etc.) → {@code @Name}
     *       — avoids ugly {@code [Ldev.relism...;@hash} output</li>
     * </ul>
     */
    private static String formatAnnotation(Annotation ann) {
        try {
            Object value = ann.annotationType().getMethod("value").invoke(ann);
            String v;
            if (value instanceof String s)   v = s;
            else if (value instanceof String[] arr) v = String.join(", ", arr);
            else return "@" + ann.annotationType().getSimpleName(); // complex type — skip value
            return "@" + ann.annotationType().getSimpleName() + "(" + v + ")";
        } catch (NoSuchMethodException ignored) {
            return "@" + ann.annotationType().getSimpleName();
        } catch (Exception e) {
            return "@" + ann.annotationType().getSimpleName();
        }
    }
}
