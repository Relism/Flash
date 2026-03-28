package dev.relism.extension;

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
 */
final class PackageScanner {

    private PackageScanner() {}

    /**
     * Returns all {@link RequestHandler} subclasses in {@code packageName} that carry
     * {@link Route @Route} and have a public no-arg constructor.
     */
    static List<Class<?>> findHandlers(String packageName) {
        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> result = new ArrayList<>();
        try {
            Enumeration<URL> resources = cl.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    scanDirectory(new File(url.toURI()), packageName, cl, result);
                } else if ("jar".equals(protocol)) {
                    String jarPath = url.getPath();
                    // jar:file:/path/to/app.jar!/com/example → /path/to/app.jar
                    String filePart = jarPath.substring(jarPath.indexOf("file:") + 5, jarPath.indexOf('!'));
                    try (JarFile jar = new JarFile(filePart)) {
                        scanJar(jar, resourcePath, packageName, cl, result);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan package: " + packageName, e);
        }
        return result;
    }

    private static void scanDirectory(File dir, String packageName, ClassLoader cl, List<Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + '.' + file.getName(), cl, result);
            } else if (file.getName().endsWith(".class") && !isAnonymous(file.getName())) {
                String className = packageName + '.' + file.getName().replace(".class", "");
                tryLoad(className, cl, result);
            }
        }
    }

    private static void scanJar(JarFile jar, String resourcePath, String packageName,
                                  ClassLoader cl, List<Class<?>> result) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(resourcePath) && name.endsWith(".class") && !isAnonymous(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                tryLoad(className, cl, result);
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
        // skip past any extra '$' (lambda desugaring may produce '$$Lambda$...')
        int next = dollar + 1;
        while (next < fileName.length() && fileName.charAt(next) == '$') next++;
        return next < fileName.length() && Character.isDigit(fileName.charAt(next));
    }

    private static void tryLoad(String className, ClassLoader cl, List<Class<?>> result) {
        try {
            Class<?> cls = cl.loadClass(className);
            if (RequestHandler.class.isAssignableFrom(cls)
                    && cls.isAnnotationPresent(Route.class)
                    && !java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
                cls.getDeclaredConstructor(); // verify no-arg constructor exists
                result.add(cls);
            }
        } catch (Exception | Error ignored) {
            // Skip classes that cannot be loaded or don't meet criteria
        }
    }
}
