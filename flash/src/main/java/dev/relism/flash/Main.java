package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class Main {

    private static final int  PORT       = 8080;
    private static final Path UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"), "flash-uploads");

    private static final Middleware CORS = next -> (req, res) -> {
        res
                .header("Access-Control-Allow-Origin",  "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if (req.method() == HttpMethod.OPTIONS) { res.status(204); return null; }
        return next.handle(req, res);
    };

    private static final Middleware ACCESS_LOG = next -> (req, res) -> {
        long start = System.nanoTime();
        try {
            return next.handle(req, res);
        } finally {
            log.info("{} {} -> {}ms", req.method(), req.path(),
                    (System.nanoTime() - start) / 1_000_000.0);
        }
    };


    public static void main(String[] args) throws Exception {
        Files.createDirectories(UPLOAD_DIR);

        FlashApp.create(PORT)
                .get("/plaintext", (req, res) -> {
                    res.type(ContentType.TEXT_PLAIN);
                    return "Hello, World!";
                }, ACCESS_LOG, CORS)
                .startAndBlock();
    }
}
