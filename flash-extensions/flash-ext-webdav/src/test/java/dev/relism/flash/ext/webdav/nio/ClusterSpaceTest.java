package dev.relism.flash.ext.webdav.nio;

import dev.relism.flash.ext.webdav.DavModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.Request;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two processes serve one directory, as two instances behind a round-robin proxy do. */
class ClusterSpaceTest {

    static final long QUOTA = 4 << 20;
    private static final String EXCLUSIVE = "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:locktype><D:write/></D:locktype></D:lockinfo>";
    private static final String USED = "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:quota-used-bytes/></D:prop></D:propfind>";

    public static final class Drive extends ClusteredNioDavHandler {
        private final Path root;
        public Drive(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root; }
        @Override protected long quota(Request req) { return QUOTA; }
    }

    /** The other process. */
    public static void main(String[] args) {
        Server server = serve(Path.of(args[0]));
        System.out.println("port=" + server.port());
        server.await();
    }

    static Server serve(Path root) {
        return Server.start(App.create().install(new DavModule()).any("/d/**", new Drive(root)), ServerConfig.builder().port(0).build());
    }

    @TempDir
    Path root;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private Server here;
    private Process there;
    private Path log;
    private int[] ports;

    @BeforeEach
    void start() throws Exception {
        here = serve(root);
        log = Files.createTempFile("shared-space", ".log");
        there = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ClusterSpaceTest.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Instant deadline = Instant.now().plusSeconds(30);
        Matcher port = null;
        while (port == null || !port.find()) {
            if (Instant.now().isAfter(deadline) || !there.isAlive()) throw new IllegalStateException(Files.readString(log));
            Thread.sleep(50);
            port = Pattern.compile("port=(\\d+)").matcher(Files.readString(log));
        }
        ports = new int[] {here.port(), Integer.parseInt(port.group(1))};
    }

    @AfterEach
    void stop() throws IOException {
        there.destroyForcibly();
        client.shutdownNow(); // its keep-alive connections would hold the server's drain open
        here.stop().join();
        Files.deleteIfExists(log);
    }

    @Test
    void aLockTakenThroughOneProcessHoldsThroughTheOther() throws Exception {
        assertEquals(201, send(0, "PUT", "/d/a.txt", "a").statusCode());
        HttpResponse<String> lock = send(1, "LOCK", "/d/a.txt", EXCLUSIVE);
        assertEquals(200, lock.statusCode());
        String token = lock.headers().firstValue("Lock-Token").orElseThrow().replaceAll("[<>]", "");

        assertEquals(423, send(0, "PUT", "/d/a.txt", "b").statusCode());
        assertEquals(204, send(0, "PUT", "/d/a.txt", "b", "If", "(<" + token + ">)").statusCode());
        assertTrue(send(0, "PROPFIND", "/d/a.txt", null, "Depth", "0").body().contains(token));
        assertEquals(204, send(0, "UNLOCK", "/d/a.txt", null, "Lock-Token", "<" + token + ">").statusCode());
        assertEquals(204, send(1, "PUT", "/d/a.txt", "c").statusCode());
    }

    /** Eight uploads stop halfway, alternating between the processes, until every block that fits is taken. */
    @Test
    void uploadsThroughBothProcessesAtOnceNeverPassTheQuota() throws Exception {
        int half = 384 << 10;
        List<Socket> uploads = new ArrayList<>();
        for (int i = 0; i < 8; i++) uploads.add(startUpload(i % 2, "/d/f" + i, half));
        await(() -> used(0) >= QUOTA && used(1) >= QUOTA, "the blocks that fit are taken");
        int stored = 0;
        for (Socket upload : uploads) if (finishUpload(upload, half) == 201) stored++;
        long bytes;
        try (Stream<Path> files = Files.list(root)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
        assertTrue(stored > 0 && bytes == stored * 2L * half && bytes <= QUOTA, stored + " files, " + bytes + " bytes");
        long written = bytes;
        await(() -> used(0) == written && used(1) == written, "both processes count what was written, and nothing more");
    }

    @Test
    void aProcessThatDiesInTheMiddleOfAnUploadGivesItsBytesBack() throws Exception {
        try (Socket hanging = startUpload(1, "/d/hang.bin", 256 << 10)) {
            await(() -> used(0) == 1 << 20, "the other process takes a block");
            there.destroyForcibly().waitFor();
        }
        long fits = QUOTA - (512 << 10);
        assertEquals(201, send(0, "PUT", "/d/big.bin", new String(new byte[(int) fits], StandardCharsets.ISO_8859_1)).statusCode());
        try (Stream<Path> parts = Files.list(root.resolve(".dav/uploads"))) {
            assertEquals(0, parts.count(), "the dead process's partial upload is deleted");
        }
        assertEquals(fits, used(0));
    }

    /** A chunked PUT that sends {@code bytes} of its body and waits. */
    private Socket startUpload(int process, String path, int bytes) throws IOException {
        Socket socket = new Socket("localhost", ports[process]);
        OutputStream out = socket.getOutputStream();
        out.write(("PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        chunk(out, bytes);
        return socket;
    }

    /** Sends the rest of an upload and its end; the status of the answer, or -1 when the server closed first. */
    private static int finishUpload(Socket socket, int bytes) {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            chunk(out, bytes);
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        } catch (IOException | RuntimeException refusedEarly) {
            return -1;
        }
    }

    private static void chunk(OutputStream out, int bytes) throws IOException {
        out.write((Integer.toHexString(bytes) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[bytes]);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private long used(int process) throws Exception {
        Matcher used = Pattern.compile("quota-used-bytes>(\\d+)<").matcher(send(process, "PROPFIND", "/d/", USED, "Depth", "0").body());
        return used.find() ? Long.parseLong(used.group(1)) : -1;
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(Condition condition, String what) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.holds()) {
            assertTrue(Instant.now().isBefore(deadline), what);
            Thread.sleep(20);
        }
    }

    private URI uri(int process, String path) {
        return URI.create("http://localhost:" + ports[process] + path);
    }

    private HttpResponse<String> send(int process, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(process, path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.ISO_8859_1));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
