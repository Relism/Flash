package dev.relism.bench;

import dev.relism.HttpServer;
import dev.relism.HttpServerConfiguration;
import dev.relism.http.ContentType;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

public class Main {

    // Lazy holder — Netty starts only on the first AHC request, not at class load.
    // Routes like /plaintext that never call AHC are unaffected.
    private static final class AhcHolder {
        static final AsyncHttpClient AHC = Dsl.asyncHttpClient(
                new DefaultAsyncHttpClientConfig.Builder()
                        .setMaxConnections(256)
                        .setMaxConnectionsPerHost(256)
                        .setKeepAlive(true)
                        .setPooledConnectionIdleTimeout(60_000)
                        .setConnectTimeout(5_000)
                        .setRequestTimeout(10_000)
                        .setIoThreadsCount(1)
                        .build()
        );
    }

    // Pre-built request objects — URI parsing done once at startup.
    private static final Request ELEMENT_REQ = new RequestBuilder("GET")
            .setUrl("http://web-data-source/element.json").build();
    private static final Request SHELLS_REQ  = new RequestBuilder("GET")
            .setUrl("http://web-data-source/shells.json").build();

    private static final byte[] PREFIX_SHELLS = "{\"shells\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUFFIX        = "}".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3000;

        HttpServer server = new HttpServer(
                HttpServerConfiguration.builder()
                        .port(port).host("0.0.0.0").build()
        );

        server.get("/api/v1/periodic-table/element", (req, res) ->
                fetchAndFilter(req.query("symbol"), false));
        server.get("/api/v1/periodic-table/shells", (req, res) ->
                fetchAndFilter(req.query("symbol"), true));
        server.get("/plaintext", (req, res) -> "Hello, World!");
        server.get("/json", (req, res) -> {
            res.setContentType(ContentType.JSON);
            return "{\"message\":\"Hello, World!\"}";
        });

        Path frontendDist = Path.of("C:\\Users\\elorc\\Documents\\Coding\\web\\PixelDocs\\.vitepress\\dist");
        StaticResourceManager cdn = new StaticResourceManager(frontendDist, "/");

        cdn.register(server);

        server.start().thenRun(() -> System.out.println("Flash Sharkbench online on port " + port));
    }

    private static dev.relism.models.Response fetchAndFilter(String symbol, boolean wrapShells) {
        if (symbol == null) return new dev.relism.models.Response(400, "Missing symbol", ContentType.TEXT_PLAIN);
        try {
            // executeRequest() returns a ListenableFuture; .get() parks the calling
            // virtual thread (cheap) while Netty's I/O thread handles the socket.
            byte[] body = AhcHolder.AHC.executeRequest(wrapShells ? SHELLS_REQ : ELEMENT_REQ)
                    .get()
                    .getResponseBodyAsBytes();

            byte[] value = extractJsonValue(body, symbol);
            if (value == null) return new dev.relism.models.Response(404, "Not Found", ContentType.TEXT_PLAIN);

            if (wrapShells) {
                byte[] wrapped = new byte[PREFIX_SHELLS.length + value.length + SUFFIX.length];
                System.arraycopy(PREFIX_SHELLS, 0, wrapped, 0,                                    PREFIX_SHELLS.length);
                System.arraycopy(value,         0, wrapped, PREFIX_SHELLS.length,                 value.length);
                System.arraycopy(SUFFIX,         0, wrapped, PREFIX_SHELLS.length + value.length, SUFFIX.length);
                return new dev.relism.models.Response(200, wrapped, ContentType.JSON);
            }

            return new dev.relism.models.Response(200, value, ContentType.JSON);
        } catch (Exception e) {
            return new dev.relism.models.Response(500, "Internal Error", ContentType.TEXT_PLAIN);
        }
    }

    /**
     * Extracts the JSON value for a given key from a flat JSON object.
     * Zero Jackson allocations — pure byte scan.
     */
    private static byte[] extractJsonValue(byte[] json, String key) {
        byte[] keyBytes = ("\"" + key + "\":").getBytes(StandardCharsets.UTF_8);

        int pos = indexOf(json, keyBytes);
        if (pos == -1) return null;
        pos += keyBytes.length;

        while (pos < json.length && json[pos] == ' ') pos++;
        if (pos >= json.length) return null;

        byte opener = json[pos];
        byte closer;
        if      (opener == '{') closer = '}';
        else if (opener == '[') closer = ']';
        else    return null;

        int depth = 0;
        boolean inString = false;
        int start = pos;

        while (pos < json.length) {
            byte b = json[pos];
            if (b == '"' && !isEscaped(json, pos)) inString = !inString;
            if (!inString) {
                if      (b == opener) depth++;
                else if (b == closer) { if (--depth == 0) { pos++; break; } }
            }
            pos++;
        }

        return Arrays.copyOfRange(json, start, pos);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean isEscaped(byte[] data, int pos) {
        int backslashes = 0;
        while (--pos >= 0 && data[pos] == '\\') backslashes++;
        return (backslashes & 1) == 1;
    }
}