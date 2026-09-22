package dev.relism.flash.ext.vite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Vite's dev server as a child process of the app: dependencies installed whenever the lockfile
 * differs from the one they were installed from, the server restarted when it changes under it,
 * and the whole process tree gone when the app stops.
 */
final class DevServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DevServer.class);

    private final Path root;
    private final int port;
    private final PackageManager.Found packages;
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("flash-vite-watch").factory());
    private Process process;
    private FileTime lockfileTime;

    DevServer(Path root, int port) {
        if (!Files.isRegularFile(root.resolve("package.json"))) {
            throw new IllegalStateException("No package.json in " + root.toAbsolutePath() + ": point ViteExtension.root(...) at the Vite project.");
        }
        this.root = root;
        this.port = port;
        this.packages = PackageManager.of(root);
        lockfileTime = modified();
        try {
            install();
            start();
        } catch (RuntimeException failed) {
            close();
            throw failed;
        }
        if (packages.lockfile() != null) watch.scheduleWithFixedDelay(this::restartIfLockfileChanged, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        watch.shutdownNow();
        stop();
    }

    private synchronized void restartIfLockfileChanged() {
        FileTime now = modified();
        if (now.equals(lockfileTime)) return;
        lockfileTime = now;
        log.info("{} changed, reinstalling and restarting Vite", packages.lockfile().getFileName());
        try {
            stop();
            install();
            start();
        } catch (RuntimeException failed) {
            log.error("Vite did not come back; fix the cause and touch the lockfile to retry", failed);
        }
    }

    /** Skipped when {@code node_modules} was installed from this very lockfile. */
    private void install() {
        Path stamp = root.resolve("node_modules/.flash-vite");
        String lockfile = packages.lockfile() == null ? "" : sha256(packages.lockfile());
        try {
            if (Files.isRegularFile(stamp) && Files.readString(stamp).equals(lockfile)) return;
            Process install = spawn(packages.install());
            if (install.waitFor() != 0) throw new IllegalStateException(String.join(" ", packages.install()) + " failed with " + install.exitValue());
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, lockfile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted installing the frontend's dependencies", e);
        }
    }

    private void start() {
        if (answers()) throw new IllegalStateException("Port " + port + " is taken: stop what uses it or set ViteExtension.devPort(...).");
        process = spawn(packages.run("dev", "--host", "127.0.0.1", "--port", String.valueOf(port), "--strictPort"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!answers()) {
            if (!process.isAlive()) throw new IllegalStateException("Vite exited with " + process.exitValue() + " before serving on port " + port + " (its output is logged above).");
            if (System.nanoTime() > deadline) throw new IllegalStateException("Vite did not answer on port " + port + " within 30 seconds.");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Vite", e);
            }
        }
        log.info("Vite is serving http://127.0.0.1:{}", port);
    }

    /** npm, pnpm and friends are wrappers: their children have to go as well, gracefully first. */
    private void stop() {
        if (process == null) return;
        List<ProcessHandle> tree = process.descendants().toList();
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception gone) {
            // Forced below.
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        process = null;
    }

    /** Starts {@code command} in the project, its output logged line by line. */
    private Process spawn(List<String> command) {
        Process started;
        try {
            started = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + command.getFirst() + ": is it installed and on the PATH?", e);
        }
        Thread.ofVirtual().start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = out.readLine()) != null; ) log.info("[vite] {}", line);
            } catch (IOException closed) {
                // The process ended.
            }
        });
        return started;
    }

    private boolean answers() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private FileTime modified() {
        try {
            return packages.lockfile() == null ? FileTime.fromMillis(0) : Files.getLastModifiedTime(packages.lockfile());
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
