package dev.relism.flash;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The classes of a package and its subpackages, from exploded directories and from jars alike. What
 * to do with them is the caller's: {@link App#scan} keeps routing annotations, a module keeps its own.
 */
public final class Scanner {

    private Scanner() {}

    /**
     * Every named class under {@code packageName}; anonymous and synthetic ones are skipped. A package
     * that is not on the classpath, or a class that fails to load, fails the boot naming it.
     */
    public static List<Class<?>> classes(String packageName) {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("A package name is required");
        String path = packageName.replace('.', '/');
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Class<?>> found = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean exists = false;
        try {
            Enumeration<URL> roots = loader.getResources(path);
            while (roots.hasMoreElements()) {
                exists = true;
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    directory(new File(root.toURI()), packageName, loader, found, failures);
                } else if ("jar".equals(root.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) root.openConnection();
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(path + '/') && isNamedClass(name))
                                load(name.substring(0, name.length() - 6).replace('/', '.'), loader, found, failures);
                        }
                    }
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException("Cannot scan " + packageName, unreadable);
        }
        if (!exists) throw new IllegalStateException("Package " + packageName + " is not on the classpath");
        if (!failures.isEmpty())
            throw new IllegalStateException("Scanning " + packageName + " failed to load:\n  " + String.join("\n  ", failures));
        return found;
    }

    private static void directory(File dir, String packageName, ClassLoader loader, List<Class<?>> found, List<String> failures) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) directory(file, packageName + '.' + name, loader, found, failures);
            else if (isNamedClass(name)) load(packageName + '.' + name.substring(0, name.length() - 6), loader, found, failures);
        }
    }

    /** A {@code .class} file that is not anonymous or synthetic ({@code Foo$1}, {@code Foo$$Lambda}). */
    private static boolean isNamedClass(String fileName) {
        if (!fileName.endsWith(".class") || fileName.endsWith("module-info.class") || fileName.endsWith("package-info.class")) return false;
        int dollar = fileName.lastIndexOf('$');
        return dollar < 0 || (dollar + 1 < fileName.length() && Character.isJavaIdentifierStart(fileName.charAt(dollar + 1)));
    }

    private static void load(String className, ClassLoader loader, List<Class<?>> found, List<String> failures) {
        try {
            found.add(loader.loadClass(className));
        } catch (ClassNotFoundException | LinkageError broken) {
            failures.add(className + ": " + broken);
        }
    }
}
