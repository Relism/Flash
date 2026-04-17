package dev.relism.ext.view;

import dev.relism.http.ContentType;
import dev.relism.routing.Route;
import dev.relism.routing.Routes;

/** Boot-time resolver that maps handler annotations to concrete render targets. */
final class ViewTargetResolver {

    private ViewTargetResolver() {}

    static ResolvedView resolve(Class<?> handlerClass, EngineCapabilities capabilities) {
        Page page = find(handlerClass, Page.class);
        Partial partial = find(handlerClass, Partial.class);

        int count = (page != null ? 1 : 0) + (partial != null ? 1 : 0);
        if (count == 0) return null;

        Route route = Routes.of(handlerClass);
        if (route == null) {
            throw new IllegalStateException("View handler " + handlerClass.getName()
                    + " has view annotation but no route annotation (@Route/@GET/@POST/...)");
        }

        if (count > 1) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " route " + route.method() + " " + route.path()
                    + " declares multiple view annotations. Use exactly one of @Page, @Partial");
        }

        if (page != null) {
            String template = page.value() == null ? "" : page.value().trim();
            if (template.isEmpty()) {
                throw new IllegalStateException("Handler " + handlerClass.getName()
                        + " route " + route.method() + " " + route.path()
                        + " uses @Page with an empty template name");
            }
            return new ResolvedView(new ViewTarget(ViewKind.PAGE, template, ""), ContentType.TEXT_HTML);
        }

        if (partial != null) {
            String template = partial.template() == null ? "" : partial.template().trim();
            if (template.isEmpty()) {
                throw new IllegalStateException("Handler " + handlerClass.getName()
                        + " route " + route.method() + " " + route.path()
                        + " uses @Partial with an empty template name");
            }
            String slot = partial.slot() == null ? "" : partial.slot().trim();
            if (!slot.isEmpty() && !capabilities.supportsPartialSlot()) {
                throw new IllegalStateException("Handler " + handlerClass.getName()
                        + " route " + route.method() + " " + route.path()
                        + " uses @Partial(slot=\"" + slot + "\") but current engine does not support partial slots");
            }
            return new ResolvedView(new ViewTarget(ViewKind.PARTIAL, template, slot), ContentType.TEXT_HTML);
        }

        return null;
    }

    private static <A extends java.lang.annotation.Annotation> A find(Class<?> cls, Class<A> type) {
        while (cls != null && !cls.equals(Object.class)) {
            A a = cls.getAnnotation(type);
            if (a != null) return a;
            cls = cls.getSuperclass();
        }
        return null;
    }

    record ResolvedView(ViewTarget target, ContentType contentType) {}
}
