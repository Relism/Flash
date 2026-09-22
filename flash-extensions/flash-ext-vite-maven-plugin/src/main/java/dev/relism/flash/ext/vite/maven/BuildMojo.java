package dev.relism.flash.ext.vite.maven;

import dev.relism.flash.ext.vite.PackageManager;
import dev.relism.flash.ext.vite.ViteExtension;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the Vite project and packages the result where {@code ViteExtension} serves it from in
 * production. Bound to {@code prepare-package}: tests never need Node, a packaged jar always has
 * its frontend.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public final class BuildMojo extends AbstractMojo {

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
                for (Path from : built.toList()) Files.copy(from, target.resolve(dist.relativize(from).toString()));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy " + dist + " to " + target, e);
        }
        getLog().info("Packaged " + dist + " as " + ViteExtension.CLASSPATH + "/");
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
