package dev.relism.ext.view.thymeleaf;

import dev.relism.http.ContentType;
import dev.relism.routing.Route;
import dev.relism.routing.Routes;

final class ThymeleafTargetResolver {
    private ThymeleafTargetResolver() {}

    static ThymeleafTarget resolve(Class<?> handlerClass, ThymeleafSettings settings) {
        Template template = find(handlerClass, Template.class);
        Fragment fragment = find(handlerClass, Fragment.class);

        int count = (template != null ? 1 : 0) + (fragment != null ? 1 : 0);
        if (count == 0) return null;

        Route route = Routes.of(handlerClass);
        if (route == null) {
            throw new IllegalStateException("Thymeleaf handler " + handlerClass.getName()
                    + " has view annotation but no route annotation (@Route/@GET/@POST/...)");
        }

        if (count > 1) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " route " + route.method() + " " + route.path()
                    + " declares multiple Thymeleaf view annotations. Use exactly one of @Template, @Fragment");
        }

        if (template != null) {
            String name = value(template.value(), "@Template", handlerClass, route);
            return new ThymeleafTarget(ThymeleafTarget.Kind.TEMPLATE, name, "", ContentType.TEXT_HTML);
        }

        String name = value(fragment.template(), "@Fragment(template)", handlerClass, route);
        String frag = fragment.value() == null ? "" : fragment.value().trim();
        if (frag.isEmpty()) frag = settings.defaultFragment();
        return new ThymeleafTarget(ThymeleafTarget.Kind.FRAGMENT, name, frag, ContentType.TEXT_HTML);
    }

    private static String value(String v, String field, Class<?> handler, Route route) {
        String value = v == null ? "" : v.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Handler " + handler.getName()
                    + " route " + route.method() + " " + route.path()
                    + " uses " + field + " with an empty template value");
        }
        return value;
    }

    private static <A extends java.lang.annotation.Annotation> A find(Class<?> cls, Class<A> type) {
        while (cls != null && !cls.equals(Object.class)) {
            A a = cls.getAnnotation(type);
            if (a != null) return a;
            cls = cls.getSuperclass();
        }
        return null;
    }
}
