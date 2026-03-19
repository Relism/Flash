package dev.relism;

import dev.relism.api.multipart.Multipart;
import dev.relism.api.multipart.Part;
import dev.relism.http.ContentType;
import dev.relism.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.List;

@Slf4j
public class Main {

    private static final int  PORT       = 8080;
    private static final Path UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"), "flash-uploads");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(UPLOAD_DIR);

        HttpServer server = new HttpServer(
                HttpServerConfiguration.builder().port(PORT).build());

        server.get("/plaintext", (req, res) -> {
            res.type(ContentType.TEXT_PLAIN);
            return "Hello, World!";
        });

        // ── File serving ──────────────────────────────────────────────────────

        server.get("/files/{name}", (req, res) -> {
            Path file = UPLOAD_DIR.resolve(req.param("name"));
            if (!Files.exists(file))
                return res.status(404).body("not found");

            return res.type(ContentType.BINARY)
                      .stream(Files.newInputStream(file), Files.size(file))
                      .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
        });

        // ── Chunked / dynamic content ─────────────────────────────────────────

        // Virtual thread produces rows into a pipe; socket thread drains in 8 KB chunks.
        // No Content-Length needed — Transfer-Encoding: chunked.
        server.get("/export/users.csv", (req, res) -> {
            PipedOutputStream sink   = new PipedOutputStream();
            PipedInputStream  source = new PipedInputStream(sink, 64 * 1024);

            Thread.ofVirtual().start(() -> {
                try (PrintWriter w = new PrintWriter(new BufferedWriter(new OutputStreamWriter(sink)))) {
                    w.println("id,name,email");
                    for (int i = 1; i <= 100_000; i++)
                        w.printf("%d,User %d,user%d@example.com%n", i, i, i);
                }
            });

            return res.type(ContentType.TEXT_CSV)
                      .chunked(source)
                      .header("Content-Disposition", "attachment; filename=\"users.csv\"");
        });

        // ── Multipart upload ──────────────────────────────────────────────────

        // Text fields are buffered eagerly; file is streamed zero-copy to disk.
        server.post("/upload/avatar", (req, res) -> {
            Multipart mp     = Multipart.of(req);
            String    userId = mp.field("userId");
            Part      avatar = mp.file("avatar");

            if (userId == null || avatar == null)
                return res.status(400).body("userId and avatar fields are required");

            Path dest = UPLOAD_DIR.resolve(userId + "_" + avatar.filename());
            Files.copy(avatar.stream(), dest, StandardCopyOption.REPLACE_EXISTING);

            return res.body("saved %s for user %s".formatted(avatar.filename(), userId));
        });

        // parts() forces a full scan and materialises all file bodies — accepted cost.
        server.post("/upload/bulk", (req, res) -> {
            List<Part> files = Multipart.of(req).parts("files");
            if (files.isEmpty())
                return res.status(HttpStatus.BAD_REQUEST).body("no files uploaded");

            StringBuilder sb = new StringBuilder();
            for (Part f : files) {
                byte[] data = f.materialize();
                Files.write(UPLOAD_DIR.resolve(f.filename()), data);
                sb.append("%s  %d bytes  %s%n".formatted(f.filename(), data.length, f.contentType()));
            }
            return res.body(sb.toString());
        });

        // Echo: file bytes flow socket-in → pipe → chunked-out.
        // The pipe decouples reading (request socket) from writing (response socket) so they
        // proceed concurrently on separate virtual threads, preventing the TCP deadlock that
        // would occur if both happened sequentially on the same thread for large files.
        server.post("/upload/echo", (req, res) -> {
            Part file = Multipart.of(req).file("file");
            if (file == null)
                return res.status(400).body("missing file field");

            PipedOutputStream sink   = new PipedOutputStream();
            PipedInputStream  source = new PipedInputStream(sink, 64 * 1024);

            InputStream fileStream = file.stream();
            Thread.ofVirtual().start(() -> {
                try { fileStream.transferTo(sink); } catch (IOException ignored) {}
                finally { try { sink.close(); } catch (IOException ignored) {} }
            });

            String ct = file.contentType();
            return res.type(ct != null ? ct : "application/octet-stream")
                      .chunked(source)
                      .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"");
        });

        // Mixed form: text fields parsed eagerly, image streamed back with Content-Length.
        server.post("/upload/resize-preview", (req, res) -> {
            Multipart mp    = Multipart.of(req);
            String    width  = mp.field("width");
            String    height = mp.field("height");
            Part      image  = mp.file("image");

            if (image == null)
                return res.status(400).body("image field required");

            // materialize() accepted here — preview endpoint, size bounded
            byte[] data = image.materialize();
            log.info("resize request {}x{} for {} ({} bytes)", width, height, image.filename(), data.length);

            return res.type(ContentType.BINARY)
                      .stream(new ByteArrayInputStream(data), data.length)
                      .header("X-Original-Name",  image.filename())
                      .header("X-Requested-Size", width + "x" + height);
        });

        server.start().thenRun(() -> log.info("Server started on :{}", PORT));
    }
}
