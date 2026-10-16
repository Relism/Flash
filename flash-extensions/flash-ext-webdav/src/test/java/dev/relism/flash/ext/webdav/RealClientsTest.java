package dev.relism.flash.ext.webdav;

import dev.relism.flash.ext.webdav.nio.StandaloneNioDavHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.security.Authenticated;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.basic.BasicAuth;
import dev.relism.flash.ext.security.form.PasswordEncoder;
import dev.relism.flash.ext.security.form.PasswordStore;
import dev.relism.flash.http.Request;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/**
 * Real clients in throwaway containers, against a file system mount behind HTTP Basic: litmus (the
 * WebDAV conformance suite), rclone and cadaver. Needs Docker; Testcontainers finds it the usual
 * ways, {@code DOCKER_HOST} included, and lets the containers reach this JVM's loopback server.
 */
@Tag("interop")
class RealClientsTest {

    @Authenticated
    public static final class Protected extends StandaloneNioDavHandler {
        private final Path root;
        public Protected(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root; }
    }

    private static final String HASH = PasswordEncoder.pbkdf2().encode("s3cret");

    @TempDir
    Path root;
    private Server server;
    private String url;

    @BeforeEach
    void start() {
        PasswordStore accounts = name -> name.equals("alice") ? new PasswordStore.Account(() -> "alice", HASH) : null;
        server = Server.start(App.create().install(new SecurityModule(), new BasicAuth(accounts), new DavModule())
                        .any("/files/**", new Protected(root)),
                ServerConfig.builder().host("127.0.0.1").port(0).build());
        Testcontainers.exposeHostPorts(server.port());
        url = "http://host.testcontainers.internal:" + server.port() + "/files/";
    }

    @AfterEach
    void stop() {
        server.stop().join();
    }

    @Test
    void litmusPassesEverySuiteWithoutWarnings() {
        try (GenericContainer<?> litmus = new GenericContainer<>("owncloud/litmus:latest")
                .withCreateContainerCmdModifier(create -> create.withEntrypoint("litmus"))
                .withCommand("-k", url, "alice", "s3cret")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(5)))) {
            litmus.start();
            String output = litmus.getLogs();
            assertEquals(5, output.split(" 0 failed\\.", -1).length - 1, output);
            assertTrue(!output.contains("WARNING") && !output.contains("FAIL") && !output.contains("SKIPPED"), output);
        }
    }

    @Test
    void rcloneCopiesChecksMovesAndPurgesATree() throws Exception {
        byte[] big = new byte[3_000_000];
        new Random(7).nextBytes(big);
        try (GenericContainer<?> rclone = idle(new GenericContainer<>("rclone/rclone:1.75.2"))
                .withCopyToContainer(Transferable.of("hello\n"), "/data/a.txt")
                .withCopyToContainer(Transferable.of(big), "/data/sub/big.bin")
                .withCopyToContainer(Transferable.of("accents"), "/data/sub/é ü.txt")) {
            rclone.start();
            run(rclone, "rclone", "config", "create", "dav", "webdav", "url=" + url, "vendor=other", "user=alice", "pass=s3cret");
            run(rclone, "rclone", "copy", "/data", "dav:tree");
            run(rclone, "rclone", "check", "/data", "dav:tree");
            run(rclone, "rclone", "moveto", "dav:tree/a.txt", "dav:tree/b.txt");
            run(rclone, "rclone", "copyto", "dav:tree/b.txt", "dav:tree/sub/c.txt");
            assertEquals(List.of("b.txt", "sub/", "sub/big.bin", "sub/c.txt", "sub/é ü.txt"),
                    run(rclone, "rclone", "lsf", "-R", "dav:tree").lines().sorted().toList());
            run(rclone, "rclone", "purge", "dav:tree");
        }
        assertEquals(List.of(), Files.list(root).toList());
    }

    @Test
    void cadaverRunsASessionScript() throws Exception {
        ImageFromDockerfile image = new ImageFromDockerfile("flash-test/cadaver", false)
                .withDockerfileFromBuilder(dockerfile -> dockerfile.from("alpine:3.22").run("apk add --no-cache cadaver").build());
        try (GenericContainer<?> cadaver = idle(new GenericContainer<>(image))
                .withCopyToContainer(Transferable.of("machine host.testcontainers.internal login alice password s3cret\n"), "/root/.netrc")
                .withCopyToContainer(Transferable.of("content\n"), "/tmp/file.txt")
                .withCopyToContainer(Transferable.of("""
                        mkcol dir
                        put /tmp/file.txt dir/file.txt
                        ls dir
                        copy dir/file.txt dir/copy.txt
                        move dir/copy.txt dir/moved.txt
                        lock dir/moved.txt
                        propset dir/moved.txt color red
                        propget dir/moved.txt color
                        unlock dir/moved.txt
                        get dir/moved.txt /tmp/back.txt
                        rm dir/file.txt
                        rm dir/moved.txt
                        rmcol dir
                        """), "/root/session")) {
            cadaver.start();
            String output = run(cadaver, "cadaver", "-r", "/root/session", url);
            assertEquals(12, output.split("succeeded\\.", -1).length - 1, output); // every command but propget
            assertTrue(output.contains("Value of color is: red") && !output.contains("failed"), output);
            assertEquals("content\n", run(cadaver, "cat", "/tmp/back.txt"));
        }
    }

    /** {@code container} waiting, for commands run in it one by one. */
    private static GenericContainer<?> idle(GenericContainer<?> container) {
        return container.withCreateContainerCmdModifier(create -> create.withEntrypoint("sleep")).withCommand("infinity");
    }

    private static String run(GenericContainer<?> container, String... command) throws Exception {
        Container.ExecResult result = container.execInContainer(command);
        assertEquals(0, result.getExitCode(), String.join(" ", command) + "\n" + result.getStdout() + result.getStderr());
        return result.getStdout();
    }
}
