package dev.relism.flash.ext.vite.maven;

import dev.relism.flash.ext.vite.PackageManager;
import dev.relism.flash.ext.vite.ViteExtension;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the Vite project and packages the result where {@code ViteExtension} serves it from in
 * production, with a maximally gzipped {@code .gz} beside each text file, so the server compresses
 * nothing. Bound to {@code prepare-package}: tests never need Node, a packaged jar always has its
 * frontend.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public final class BuildMojo extends AbstractMojo {

    /** Formats that are not compressed already; images and woff gain nothing from gzip. */
    private static final Set<String> COMPRESSIBLE = Set.of("html", "js", "mjs", "css", "json", "map", "webmanifest", "txt", "xml", "svg", "ttf", "otf", "wasm");

    /** The Vite project. */
    @Parameter(property = "flash.vite.root", defaultValue = "${project.basedir}/web")
    File root;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    File classes;

    /** Leaves the frontend out, for a build that only needs the backend. */
    @Parameter(property = "flash.vite.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping the frontend build");
            return;
        }
        Path project = root.toPath();
        if (!Files.isRegularFile(project.resolve("package.json"))) {
            throw new MojoExecutionException("No package.json in " + project + ": set <root> to the Vite project.");
        }
        PackageManager.Found packages = PackageManager.of(project);
        run(project, packages.install());
        run(project, packages.run("build"));
        Path dist = project.resolve("dist");
        if (!Files.isRegularFile(dist.resolve("index.html"))) {
            throw new MojoExecutionException("The build left no dist/index.html in " + project + ".");
        }
        Path target = classes.toPath().resolve(ViteExtension.CLASSPATH);
        try {
            if (Files.exists(target)) {
                try (Stream<Path> old = Files.walk(target)) {
                    for (Path path : old.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            Files.createDirectories(target.getParent());
            try (Stream<Path> built = Files.walk(dist)) {
                for (Path from : built.toList()) {
                    Path to = target.resolve(dist.relativize(from).toString());
                    Files.copy(from, to);
                    if (Files.isRegularFile(to)) gzip(to);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy " + dist + " to " + target, e);
        }
        getLog().info("Packaged " + dist + " as " + ViteExtension.CLASSPATH + "/");
    }

    /** Only worth it from 1 KB, and only kept when it is smaller. */
    private static void gzip(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (!COMPRESSIBLE.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase()) || Files.size(file) < 1024) return;
        byte[] raw = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
        try (GZIPOutputStream zip = new GZIPOutputStream(out) {{ def.setLevel(Deflater.BEST_COMPRESSION); }}) {
            zip.write(raw);
        }
        if (out.size() < raw.length) Files.write(file.resolveSibling(name + ".gz"), out.toByteArray());
    }

    private static void run(Path project, List<String> command) throws MojoExecutionException {
        int exit;
        try {
            exit = new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot run " + command.getFirst() + ": is Node installed and " + command.getFirst() + " on the PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted running " + String.join(" ", command), e);
        }
        if (exit != 0) throw new MojoExecutionException(String.join(" ", command) + " failed with " + exit + " (its output is above).");
    }
}
