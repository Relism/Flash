package dev.relism.flash.extension;

import dev.relism.flash.exceptions.InitializationException;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.Routes;
import dev.relism.flash.routing.Ws;
import dev.relism.flash.websocket.WebSocketEndpoint;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Minimal classpath scanner used by {@link FlashApp#scan} and {@link FlashScope#scan}.
 * Finds {@link RequestHandler} subclasses in a package that carry a routing annotation
 * ({@link Route @Route} or shorthand {@code @GET}, {@code @POST}, …).
 * Supports both exploded directories (development) and fat JARs (deployment).
 *
 * <p><b>Fail-fast:</b> if the package does not exist, contains no handlers, or a handler
 * class cannot be loaded, an {@link InitializationException} is thrown immediately.
 * A clear crash at boot is always preferable to a server that starts "empty".
 */
public final class PackageScanner {

    private PackageScanner() {}

    record ScanResult(List<Class<?>> httpHandlers, List<Class<?>> wsEndpoints) {}

    /**
     * Handlers in {@code packageName} that have {@link Routes#of(Class) resolvable} route metadata.
     *
     * @throws InitializationException if the package is empty, does not exist, or a
     *                                  handler class fails to load
     */
    static List<Class<?>> findHandlers(String packageName) {
        return scan(packageName).httpHandlers();
    }

    /**
     * Shared deterministic classpath discovery primitive for Flash extensions. It owns the
     * directory/JAR traversal, anonymous-class exclusion and load diagnostics; extensions only
     * classify the returned classes according to their own component contract.
     */
    public static List<Class<?>> discover(String packageName) {
        if (packageName == null || packageName.isBlank())
            throw new InitializationException("scan() called with null or blank package name");
        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean found = false;
        try {
            Enumeration<URL> resources = cl.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                found = true;
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) discoverDirectory(new File(url.toURI()), packageName, cl, result, errors);
                else if ("jar".equals(url.getProtocol())) {
                    String path = url.getPath();
                    try (JarFile jar = new JarFile(path.substring(path.indexOf("file:") + 5, path.indexOf('!')))) {
                        discoverJar(jar, resourcePath, cl, result, errors);
                    }
                }
            }
        } catch (Exception e) { throw new InitializationException("Failed to scan package: " + packageName, e); }
        if (!found) throw new InitializationException("scan(\"" + packageName + "\") — package not found on classpath");
        if (!errors.isEmpty()) throw new InitializationException("scan(\"" + packageName + "\") — failed to load classes:\n  • " + String.join("\n  • ", errors));
        return List.copyOf(result);
    }

    private static void discoverDirectory(File dir, String packageName, ClassLoader cl, List<Class<?>> result, List<String> errors) {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) discoverDirectory(file, packageName + '.' + file.getName(), cl, result, errors);
            else if (file.getName().endsWith(".class") && !isAnonymous(file.getName())) discoverClass(packageName + '.' + file.getName().replace(".class", ""), cl, result, errors);
        }
    }

    private static void discoverJar(JarFile jar, String resourcePath, ClassLoader cl, List<Class<?>> result, List<String> errors) {
        String prefix = resourcePath + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && name.endsWith(".class") && !isAnonymous(name)) discoverClass(name.replace('/', '.').replace(".class", ""), cl, result, errors);
        }
    }

    private static void discoverClass(String name, ClassLoader cl, List<Class<?>> result, List<String> errors) {
        try { result.add(cl.loadClass(name)); }
        catch (ClassNotFoundException | LinkageError e) { errors.add(name + " — " + e.getMessage()); }
    }

    static ScanResult scan(String packageName) {
        if (packageName == null || packageName.isBlank())
            throw new InitializationException("scan() called with null or blank package name");

        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> http = new ArrayList<>();
        List<Class<?>> ws = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean packageFound = false;

        try {
            Enumeration<URL> resources = cl.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                packageFound = true;
                URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    scanDirectory(new File(url.toURI()), packageName, cl, http, ws, errors);
                } else if ("jar".equals(protocol)) {
                    String jarPath = url.getPath();
                    String filePart = jarPath.substring(jarPath.indexOf("file:") + 5, jarPath.indexOf('!'));
                    try (JarFile jar = new JarFile(filePart)) {
                        scanJar(jar, resourcePath, packageName, cl, http, ws, errors);
                    }
                }
            }
        } catch (InitializationException e) {
            throw e;
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

        if (http.isEmpty() && ws.isEmpty())
            throw new InitializationException(
                    "scan(\"" + packageName + "\") — no routable handlers found. " +
                    "Ensure classes extend RequestHandler or WebSocketEndpoint, declare the right route annotation, " +
                    "are not abstract, and have a public no-arg constructor.");

        return new ScanResult(List.copyOf(http), List.copyOf(ws));
    }

    private static void scanDirectory(File dir, String packageName, ClassLoader cl,
                                        List<Class<?>> http, List<Class<?>> ws, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + '.' + file.getName(), cl, http, ws, errors);
            } else if (file.getName().endsWith(".class") && !isAnonymous(file.getName())) {
                String className = packageName + '.' + file.getName().replace(".class", "");
                tryLoad(className, cl, http, ws, errors);
            }
        }
    }

    private static void scanJar(JarFile jar, String resourcePath, String packageName,
                                  ClassLoader cl, List<Class<?>> http, List<Class<?>> ws, List<String> errors) {
        String prefix = resourcePath + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && name.endsWith(".class") && !isAnonymous(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                tryLoad(className, cl, http, ws, errors);
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
                                 List<Class<?>> http, List<Class<?>> ws, List<String> errors) {
        try {
            Class<?> cls = cl.loadClass(className);
            if (Modifier.isAbstract(cls.getModifiers())) return;

            if (RequestHandler.class.isAssignableFrom(cls)
                    && Routes.of(cls) != null
                    && !cls.isAnnotationPresent(Ws.class)) {
                assertNoArgConstructor(cls, errors);
                http.add(cls);
                return;
            }

            if (WebSocketEndpoint.class.isAssignableFrom(cls)
                    && cls.isAnnotationPresent(Ws.class)) {
                assertNoArgConstructor(cls, errors);
                ws.add(cls);
                return;
            }
        } catch (ClassNotFoundException e) {
            errors.add(className + " — class not found: " + e.getMessage());
        } catch (NoClassDefFoundError e) {
            errors.add(className + " — missing dependency: " + e.getMessage());
        } catch (LinkageError e) {
            errors.add(className + " — linkage error: " + e.getMessage());
        }
    }

    private static void assertNoArgConstructor(Class<?> cls, List<String> errors) {
        try { cls.getDeclaredConstructor(); }
        catch (NoSuchMethodException e) { errors.add(cls.getName() + " — missing public no-arg constructor"); }
    }
}
