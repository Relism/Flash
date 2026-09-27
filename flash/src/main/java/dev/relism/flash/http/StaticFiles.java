package dev.relism.flash.http;

import dev.relism.fpr.core.ByteView;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static dev.relism.flash.bytes.ByteScan.indexOfIgnoreCase;

/**
 * Files read once from the classpath and served from memory: every byte a response carries, headers
 * included, is prepared at boot, so serving allocates nothing. A {@code .gz} beside a file is its
 * gzipped form, sent to clients that accept it. A file whose name {@code immutable} accepts (a
 * content-hashed one) is cached for good; the rest revalidate by ETag. An {@code index.html} also
 * answers at its directory.
 *
 * <pre>{@code
 * StaticFiles files = new StaticFiles(loader, "static", "/static", StaticFiles.HASHED);
 * app.get("/static/**", files);
 * }</pre>
 */
public final class StaticFiles implements Handler {

    /** A name with a hex content hash of 8 or more digits between dots: {@code app.3f2a91bc.js}. */
    public static final Predicate<String> HASHED = name -> name.matches(".*\\.[a-fA-F0-9]{8,}\\.[^/]*");

    private static final PreEncodedHeader IMMUTABLE = new PreEncodedHeader("Cache-Control", "public, max-age=31536000, immutable");
    private static final PreEncodedHeader REVALIDATE = new PreEncodedHeader("Cache-Control", "no-cache");
    private static final PreEncodedHeader VARY = new PreEncodedHeader("Vary", "Accept-Encoding");
    private static final PreEncodedHeader GZIP = new PreEncodedHeader("Content-Encoding", "gzip");
    private static final byte[] GZIP_TOKEN = "gzip".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Q = "q=".getBytes(StandardCharsets.US_ASCII);

    private record File(byte[] raw, byte[] gzip, byte[] type, PreEncodedHeader cache, PreEncodedHeader etag, byte[] tag) {}

    private final Map<String, File> byPath;

    /**
     * Every file under the classpath directory {@code root} of {@code loader}, each at {@code
     * basePath} plus its path below {@code root}.
     *
     * @throws IllegalStateException when {@code root} is not on the classpath
     */
    public StaticFiles(ClassLoader loader, String root, String basePath, Predicate<String> immutable) {
        URL found = loader.getResource(root);
        if (found == null) throw new IllegalStateException("No '" + root + "' directory on the classpath");
        String prefix = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        Map<String, File> files = new HashMap<>();
        try {
            URI uri = found.toURI();
            FileSystem opened = null;
            if ("jar".equals(uri.getScheme())) {
                try {
                    opened = FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException alreadyOpen) {
                    // Path.of below resolves against the one already open.
                }
            }
            Path dir = Path.of(uri);
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    String relative = dir.relativize(path).toString().replace('\\', '/');
                    if (relative.endsWith(".gz")) continue;
                    Path gzip = path.resolveSibling(path.getFileName() + ".gz");
                    File file = file(relative, Files.readAllBytes(path), Files.isRegularFile(gzip) ? Files.readAllBytes(gzip) : null, immutable);
                    files.put(prefix + "/" + relative, file);
                    if (relative.equals("index.html") || relative.endsWith("/index.html")) {
                        String directory = prefix + "/" + relative.substring(0, relative.length() - "index.html".length());
                        files.put(directory, file);
                        if (directory.length() > 1) files.put(directory.substring(0, directory.length() - 1), file);
                    }
                }
            } finally {
                if (opened != null) opened.close();
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Cannot read the static files at " + found, e);
        }
        this.byPath = Map.copyOf(files);
    }

    @Override
    public Object handle(Request req, Response res) {
        if (!serve(req.path(), req, res)) throw HttpException.notFound(req.path());
        return null;
    }

    /** Answers {@code req} with the file at {@code path}; false, with nothing written, when there is none. */
    public boolean serve(String path, Request req, Response res) {
        File file = byPath.get(path);
        if (file == null) return false;
        res.header(file.cache);
        if (file.etag != null) {
            res.header(file.etag);
            if (indexOfIgnoreCase(req.headerView("If-None-Match"), file.tag, 0) >= 0) {
                res.status(HttpStatus.NOT_MODIFIED);
                return true;
            }
        }
        res.type(file.type);
        if (file.gzip != null) {
            res.header(VARY);
            if (acceptsGzip(req.headerView("Accept-Encoding"))) {
                res.header(GZIP).body(file.gzip);
                return true;
            }
        }
        res.body(file.raw);
        return true;
    }

    /** The paths served, for a test or a listing. */
    public Set<String> paths() {
        return byPath.keySet();
    }

    /** Listed in {@code Accept-Encoding}, and not with a q of zero. */
    static boolean acceptsGzip(ByteView accept) {
        int at = indexOfIgnoreCase(accept, GZIP_TOKEN, 0);
        if (at < 0) return false;
        int end = at;
        while (end < accept.length() && accept.byteAt(end) != ',') end++;
        int q = indexOfIgnoreCase(accept, Q, at);
        if (q < 0 || q > end) return true;
        for (int i = q + 2; i < end; i++) {
            byte c = accept.byteAt(i);
            if (c >= '1' && c <= '9') return true;
            if (c != '0' && c != '.') break;
        }
        return false;
    }

    private static File file(String path, byte[] raw, byte[] gzip, Predicate<String> immutable) {
        byte[] type = ContentType.ofExtension(path.substring(path.lastIndexOf('.') + 1)).getBytes(StandardCharsets.US_ASCII);
        if (immutable.test(path)) return new File(raw, gzip, type, IMMUTABLE, null, null);
        // A hex digest: case folding in the If-None-Match search leaves it unchanged.
        String tag = "\"" + HexFormat.of().formatHex(sha1(raw)) + "\"";
        return new File(raw, gzip, type, REVALIDATE, new PreEncodedHeader("ETag", tag), tag.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha1(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
