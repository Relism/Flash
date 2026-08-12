package dev.relism.flash.ext.mcp;

import dev.relism.flash.exceptions.InitializationException;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import dev.relism.flash.extension.PackageScanner;

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

        List<Class<? extends McpTool>> tools = new ArrayList<>();
        List<Class<? extends McpResource>> resources = new ArrayList<>();
        List<Class<? extends McpPrompt>> prompts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Class<?> cls : PackageScanner.discover(packageName)) tryLoad(cls, tools, resources, prompts, errors);

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

    @SuppressWarnings("unchecked")
    private static void tryLoad(Class<?> cls,
                                 List<Class<? extends McpTool>> tools,
                                 List<Class<? extends McpResource>> resources,
                                 List<Class<? extends McpPrompt>> prompts,
                                 List<String> errors) {
        try {
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
        } catch (LinkageError e) { errors.add(cls.getName() + " — linkage error: " + e.getMessage()); }
    }

    private static void assertNoArgConstructor(Class<?> cls, List<String> errors) {
        try { cls.getDeclaredConstructor(); }
        catch (NoSuchMethodException e) { errors.add(cls.getName() + " — missing public no-arg constructor"); }
    }
}
