package dev.relism.flash.ext.view.jte;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.Routes;

final class JteTargetResolver {
    private JteTargetResolver() {}

    static JteTarget resolve(Class<?> handlerClass, JteSettings settings) {
        Template template = find(handlerClass, Template.class);
        if (template == null) return null;

        Route route = Routes.of(handlerClass);
        if (route == null) {
            throw new IllegalStateException("Jte handler " + handlerClass.getName()
                    + " has @Template but no route annotation (@Route/@GET/@POST/...)");
        }

        String name = template.value() == null ? "" : template.value().trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " route " + route.method() + " " + route.path()
                    + " uses @Template with an empty value");
        }

        return new JteTarget(name, toHttpContentType(settings.contentType()));
    }

    private static ContentType toHttpContentType(gg.jte.ContentType contentType) {
        return contentType == gg.jte.ContentType.Plain ? ContentType.TEXT_PLAIN : ContentType.TEXT_HTML;
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
