package dev.relism.ext.webbundler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class CommandOrchestrator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommandOrchestrator.class);

    private final WebBundlerConfig config;
    private final CommandSafetyPolicy safetyPolicy;
    private final ExecutorService logPool = Executors.newCachedThreadPool(daemonThreadFactory("flash-web-bundler-log"));
    private Process devProcess;

    CommandOrchestrator(WebBundlerConfig config, CommandSafetyPolicy safetyPolicy) {
        this.config = config;
        this.safetyPolicy = safetyPolicy;
    }

    void runBlocking(List<String> command, Path cwd) {
        safetyPolicy.check(command);
        Process process = start(command, cwd);
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("Command failed (" + code + "): " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting process", e);
        }
    }

    void startDevServer(List<String> command, Path cwd) {
        if (devProcess != null && devProcess.isAlive()) return;
        if (isPortOccupied(config.devHost(), config.devPort())) {
            throw new IllegalStateException("Dev server port already occupied: " + config.devHost() + ":" + config.devPort());
        }
        safetyPolicy.check(command);
        devProcess = start(command, cwd);
    }

    void healthcheck(String healthPath, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String url = "http://" + config.devHost() + ":" + config.devPort() + healthPath;
        while (System.currentTimeMillis() < deadline) {
            if (devProcess == null || !devProcess.isAlive()) {
                throw new IllegalStateException("Dev process exited before becoming healthy");
            }
            if (ping(url)) return;
            sleep(200L);
        }
        throw new IllegalStateException("Dev server healthcheck timeout for " + url);
    }

    void restartDevServer(List<String> command, Path cwd) {
        stopDevServer();
        startDevServer(command, cwd);
    }

    void stopDevServer() {
        if (devProcess == null) return;
        destroyProcessTree(devProcess, 5);
        devProcess = null;
    }

    @Override
    public void close() {
        stopDevServer();
        logPool.shutdownNow();
    }

    private Process start(List<String> command, Path cwd) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        try {
            Process process = pb.start();
            attachLogs(process, command.get(0));
            return process;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch process: " + String.join(" ", command), e);
        }
    }

    private void attachLogs(Process process, String name) {
        if (config.loggingMode() == LoggingMode.QUIET) return;
        boolean merge = config.loggingMode() == LoggingMode.MERGED || config.loggingMode() == LoggingMode.VERBOSE;
        if (merge) {
            logPool.submit(() -> readStream(process.getInputStream(), "[" + name + "] "));
            logPool.submit(() -> readStream(process.getErrorStream(), "[" + name + "] "));
        } else {
            logPool.submit(() -> readStream(process.getInputStream(), "[" + name + ":out] "));
            logPool.submit(() -> readStream(process.getErrorStream(), "[" + name + ":err] "));
        }
    }

    private void readStream(java.io.InputStream in, String prefix) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (config.loggingMode() == LoggingMode.VERBOSE) log.info("{}{}", prefix, line);
                else log.debug("{}{}", prefix, line);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean ping(String rawUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPortOccupied(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void destroyProcessTree(Process process, int gracefulSeconds) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = root.descendants().toList();

        // First pass: graceful termination for wrapper + children.
        for (ProcessHandle child : descendants) child.destroy();
        root.destroy();

        waitForExit(root, gracefulSeconds);

        // Second pass: force-kill anything still alive (important on Windows cmd wrappers).
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) child.destroyForcibly();
        }
        if (root.isAlive()) root.destroyForcibly();

        waitForExit(root, 2);
    }

    private static void waitForExit(ProcessHandle handle, int timeoutSeconds) {
        try {
            handle.onExit().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort shutdown; callers handle remaining state.
        }
    }
}
