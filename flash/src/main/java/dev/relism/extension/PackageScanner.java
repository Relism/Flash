package dev.relism.extension;

import dev.relism.exceptions.InitializationException;
import dev.relism.models.RequestHandler;
import dev.relism.routing.Route;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Minimal classpath scanner used by {@link FlashApp#scan} and {@link FlashScope#scan}.
 * Finds all classes in a package that extend {@link RequestHandler} and carry {@link Route @Route}.
 * Supports both exploded directories (development) and fat JARs (deployment).
 *
 * <p><b>Fail-fast:</b> if the package does not exist, contains no handlers, or a handler
 * class cannot be loaded, an {@link InitializationException} is thrown immediately.
 * A clear crash at boot is always preferable to a server that starts "empty".
 */
final class PackageScanner {

    private PackageScanner() {}

    /**
     * Returns all {@link RequestHandler} subclasses in {@code packageName} that carry
     * {@link Route @Route}.
     *
     * @throws InitializationException if the package is empty, does not exist, or a
     *                                  handler class fails to load
     */
    static List<Class<?>> findHandlers(String packageName) {
        if (packageName == null || packageName.isBlank())
            throw new InitializationException("scan() called with null or blank package name");

        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean packageFound = false;

        try {
            Enumeration<URL> resources = cl.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                packageFound = true;
                URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    scanDirectory(new File(url.toURI()), packageName, cl, result, errors);
                } else if ("jar".equals(protocol)) {
                    String jarPath = url.getPath();
                    String filePart = jarPath.substring(jarPath.indexOf("file:") + 5, jarPath.indexOf('!'));
                    try (JarFile jar = new JarFile(filePart)) {
                        scanJar(jar, resourcePath, packageName, cl, result, errors);
                    }
                }
            }
        } catch (InitializationException e) {
            throw e; // re-throw our own exceptions
        } catch (Exception e) {
            throw new InitializationException("Failed to scan package: " + packageName, e);
        }

        if (!packageFound)
            throw new InitializationException(
                    "scan(\"" + packageName + "\") — package not found on classpath. " +
                    "Verify the package name and ensure the module is on the classpath.");

        if (!errors.isEmpty())
            throw new InitializationException(
                    "scan(\"" + packageName + "\") — failed to load " + errors.size() + " handler(s):\n  • " +
                    String.join("\n  • ", errors));

        if (result.isEmpty())
            throw new InitializationException(
                    "scan(\"" + packageName + "\") — no @Route handlers found. " +
                    "Ensure handler classes extend RequestHandler, carry @Route, are not abstract, " +
                    "and have a public no-arg constructor.");

        return result;
    }

    private static void scanDirectory(File dir, String packageName, ClassLoader cl,
                                       List<Class<?>> result, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + '.' + file.getName(), cl, result, errors);
            } else if (file.getName().endsWith(".class") && !isAnonymous(file.getName())) {
                String className = packageName + '.' + file.getName().replace(".class", "");
                tryLoad(className, cl, result, errors);
            }
        }
    }

    private static void scanJar(JarFile jar, String resourcePath, String packageName,
                                  ClassLoader cl, List<Class<?>> result, List<String> errors) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(resourcePath) && name.endsWith(".class") && !isAnonymous(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                tryLoad(className, cl, result, errors);
            }
        }
    }

    /**
     * Returns {@code true} for anonymous/synthetic class files whose name segment after
     * the last {@code $} starts with a digit (e.g. {@code Foo$1.class},
     * {@code Foo$$Lambda$14.class}). Named static nested classes like
     * {@code Outer$Inner.class} return {@code false} and are eligible for scanning.
     */
    private static boolean isAnonymous(String fileName) {
        int dollar = fileName.lastIndexOf('$');
        if (dollar < 0) return false;
        int next = dollar + 1;
        while (next < fileName.length() && fileName.charAt(next) == '$') next++;
        return next < fileName.length() && Character.isDigit(fileName.charAt(next));
    }

    private static void tryLoad(String className, ClassLoader cl,
                                 List<Class<?>> result, List<String> errors) {
        try {
            Class<?> cls = cl.loadClass(className);
            if (!RequestHandler.class.isAssignableFrom(cls)) return;
            if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) return;
            if (!cls.isAnnotationPresent(Route.class)) return;

            // Verify no-arg constructor exists — fail-fast if missing
            try {
                cls.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                errors.add(className + " — missing public no-arg constructor");
                return;
            }

            result.add(cls);
        } catch (ClassNotFoundException e) {
            errors.add(className + " — class not found: " + e.getMessage());
        } catch (NoClassDefFoundError e) {
            errors.add(className + " — missing dependency: " + e.getMessage());
        } catch (LinkageError e) {
            errors.add(className + " — linkage error: " + e.getMessage());
        }
    }
}
