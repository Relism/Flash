package dev.relism.flash.ext.mcp;

import dev.relism.flash.exceptions.InitializationException;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Minimal classpath scanner used by {@link McpConfig#toolsPackage(String)}. Finds
 * {@link McpTool}/{@link McpResource}/{@link McpPrompt} subclasses carrying the matching
 * annotation ({@link Tool @Tool}, {@link Resource @Resource}, {@link Prompt @Prompt}).
 * Supports both exploded directories (development) and fat JARs (deployment).
 *
 * <p>Deliberately not shared with {@code dev.relism.flash.extension.PackageScanner}: that
 * scanner is package-private and hardcoded to {@code RequestHandler}/{@code WebSocketEndpoint}.
 * The directory/JAR walking logic below intentionally mirrors it — same fail-fast contract,
 * same anonymous-class filtering.
 *
 * <p><b>Fail-fast:</b> if the package does not exist, contains no matching class, or a class
 * cannot be loaded, an {@link InitializationException} is thrown immediately at boot.
 */
final class McpPackageScanner {

    private McpPackageScanner() {}

    record ScanResult(List<Class<? extends McpTool>> tools,
                       List<Class<? extends McpResource>> resources,
                       List<Class<? extends McpPrompt>> prompts) {}

    static ScanResult scan(String packageName) {
        if (packageName == null || packageName.isBlank())
            throw new InitializationException("McpConfig.toolsPackage() called with null or blank package name");

        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<? extends McpTool>> tools = new ArrayList<>();
        List<Class<? extends McpResource>> resources = new ArrayList<>();
        List<Class<? extends McpPrompt>> prompts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean packageFound = false;

        try {
            Enumeration<URL> urls = cl.getResources(resourcePath);
            while (urls.hasMoreElements()) {
                packageFound = true;
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    scanDirectory(new File(url.toURI()), packageName, cl, tools, resources, prompts, errors);
                } else if ("jar".equals(protocol)) {
                    String jarPath = url.getPath();
                    String filePart = jarPath.substring(jarPath.indexOf("file:") + 5, jarPath.indexOf('!'));
                    try (JarFile jar = new JarFile(filePart)) {
                        scanJar(jar, resourcePath, cl, tools, resources, prompts, errors);
                    }
                }
            }
        } catch (InitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new InitializationException("Failed to scan MCP package: " + packageName, e);
        }

        if (!packageFound)
            throw new InitializationException(
                    "McpConfig.toolsPackage(\"" + packageName + "\") — package not found on classpath. " +
                    "Verify the package name and ensure the module is on the classpath.");

        if (!errors.isEmpty())
            throw new InitializationException(
                    "McpConfig.toolsPackage(\"" + packageName + "\") — failed to load " + errors.size() + " class(es):\n  • " +
                    String.join("\n  • ", errors));

        if (tools.isEmpty() && resources.isEmpty() && prompts.isEmpty())
            throw new InitializationException(
                    "McpConfig.toolsPackage(\"" + packageName + "\") — no @Tool/@Resource/@Prompt classes found. " +
                    "Ensure classes extend McpTool/McpResource/McpPrompt, carry the matching annotation, " +
                    "are not abstract, and have a public no-arg constructor.");

        return new ScanResult(List.copyOf(tools), List.copyOf(resources), List.copyOf(prompts));
    }

    private static void scanDirectory(File dir, String packageName, ClassLoader cl,
                                       List<Class<? extends McpTool>> tools,
                                       List<Class<? extends McpResource>> resources,
                                       List<Class<? extends McpPrompt>> prompts,
                                       List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + '.' + file.getName(), cl, tools, resources, prompts, errors);
            } else if (file.getName().endsWith(".class") && !isAnonymous(file.getName())) {
                String className = packageName + '.' + file.getName().replace(".class", "");
                tryLoad(className, cl, tools, resources, prompts, errors);
            }
        }
    }

    private static void scanJar(JarFile jar, String resourcePath, ClassLoader cl,
                                 List<Class<? extends McpTool>> tools,
                                 List<Class<? extends McpResource>> resources,
                                 List<Class<? extends McpPrompt>> prompts,
                                 List<String> errors) {
        String prefix = resourcePath + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && name.endsWith(".class") && !isAnonymous(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                tryLoad(className, cl, tools, resources, prompts, errors);
            }
        }
    }

    private static boolean isAnonymous(String fileName) {
        int dollar = fileName.lastIndexOf('$');
        if (dollar < 0) return false;
        int next = dollar + 1;
        while (next < fileName.length() && fileName.charAt(next) == '$') next++;
        return next < fileName.length() && Character.isDigit(fileName.charAt(next));
    }

    @SuppressWarnings("unchecked")
    private static void tryLoad(String className, ClassLoader cl,
                                 List<Class<? extends McpTool>> tools,
                                 List<Class<? extends McpResource>> resources,
                                 List<Class<? extends McpPrompt>> prompts,
                                 List<String> errors) {
        try {
            Class<?> cls = cl.loadClass(className);
            if (Modifier.isAbstract(cls.getModifiers())) return;

            if (McpTool.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Tool.class)) {
                assertNoArgConstructor(cls, errors);
                tools.add((Class<? extends McpTool>) cls);
                return;
            }
            if (McpResource.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Resource.class)) {
                assertNoArgConstructor(cls, errors);
                resources.add((Class<? extends McpResource>) cls);
                return;
            }
            if (McpPrompt.class.isAssignableFrom(cls) && cls.isAnnotationPresent(Prompt.class)) {
                assertNoArgConstructor(cls, errors);
                prompts.add((Class<? extends McpPrompt>) cls);
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
