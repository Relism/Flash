package dev.relism.ext.view;

import dev.relism.http.ContentType;
import dev.relism.routing.Route;
import dev.relism.routing.Routes;

final class ViewTargetResolver {

    private ViewTargetResolver() {}

    static ResolvedView resolve(Class<?> handlerClass, EngineCapabilities capabilities) {
        Page page = find(handlerClass, Page.class);
        Partial partial = find(handlerClass, Partial.class);
        View legacy = find(handlerClass, View.class);

        int count = (page != null ? 1 : 0) + (partial != null ? 1 : 0) + (legacy != null ? 1 : 0);
        if (count == 0) return null;

        Route route = Routes.of(handlerClass);
        if (route == null) {
            throw new IllegalStateException("View handler " + handlerClass.getName()
                    + " has view annotation but no route annotation (@Route/@GET/@POST/...)");
        }

        if (count > 1) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " route " + route.method() + " " + route.path()
                    + " declares multiple view annotations. Use exactly one of @Page, @Partial, @View");
        }

        if (page != null) {
            return new ResolvedView(new ViewTarget(ViewKind.PAGE, page.value(), ""), ContentType.TEXT_HTML, false);
        }

        if (partial != null) {
            String slot = partial.slot() == null ? "" : partial.slot().trim();
            if (!slot.isEmpty() && !capabilities.supportsPartialSlot()) {
                throw new IllegalStateException("Handler " + handlerClass.getName()
                        + " route " + route.method() + " " + route.path()
                        + " uses @Partial(slot=\"" + slot + "\") but current engine does not support partial slots");
            }
            return new ResolvedView(new ViewTarget(ViewKind.PARTIAL, partial.template(), slot), ContentType.TEXT_HTML, false);
        }

        return new ResolvedView(
                new ViewTarget(legacy.fragment() ? ViewKind.PARTIAL : ViewKind.PAGE, legacy.value(), ""),
                legacy.contentType(),
                true
        );
    }

    private static <A extends java.lang.annotation.Annotation> A find(Class<?> cls, Class<A> type) {
        while (cls != null && !cls.equals(Object.class)) {
            A a = cls.getAnnotation(type);
            if (a != null) return a;
            cls = cls.getSuperclass();
        }
        return null;
    }

    record ResolvedView(ViewTarget target, ContentType contentType, boolean legacy) {}
}
