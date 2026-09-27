package dev.relism.flash;

import java.util.function.Supplier;

/**
 * A reusable piece of an {@link App}: routes, middleware, services. An extension is a module
 * shipped in a jar, and an application splits itself into modules the same way.
 *
 * <pre>{@code
 * public final class Billing implements Module {
 *     @Override public void install(App app) {
 *         app.add(Invoices.class).scan("com.acme.billing");
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface Module {

    void install(App app);

    /**
     * {@code module} when {@code className} is on the classpath, a module that does nothing
     * otherwise: how a contribution to an optional dependency is installed without loading it.
     */
    static Module ifPresent(String className, Supplier<Module> module) {
        try {
            Class.forName(className, false, Module.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return app -> {};
        }
        return module.get();
    }
}
