package dev.relism.flash.ext.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** NDJSON over HTTP, with both JSON codecs: lines in and out, errors before and after the first byte. */
class NdjsonTest {

    /** Doubles every row it reads, one answer line per request line. */
    public static final class Doubler extends NdjsonHandler<Row, Row> {
        @Override protected void handle(Request request, NdjsonInput<Row> in, NdjsonOutput<Row> out) throws Exception {
            for (Row row; (row = in.next()) != null; ) {
                out.send(new Row(row.name(), row.quantity() * 2));
                if (request.header("x-flush") != null) out.flush();
            }
        }
    }

    /** Output only: a live feed that waits for the test between two lines. */
    public static final class Feed extends NdjsonHandler<Void, Row> {
        static volatile CountDownLatch next = new CountDownLatch(1);

        @Override protected void handle(Request request, NdjsonInput<Void> in, NdjsonOutput<Row> out) throws Exception {
            assertEquals(null, in.next());
            out.send(new Row("first", 1));
            out.flush();
            assertTrue(next.await(5, TimeUnit.SECONDS));
            out.send(new Row("second", 2));
        }
    }

    /** Input only: counts what it read into a header-less, body-less answer. */
    public static final class Sink extends NdjsonHandler<Row, Void> {
        static volatile int read;

        @Override protected void handle(Request request, NdjsonInput<Row> in, NdjsonOutput<Void> out) throws Exception {
            int count = 0;
            while (in.next() != null) count++;
            read = count;
            assertThrows(IllegalStateException.class, () -> out.send(null));
        }
    }

    private Server server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        client.close();
        if (server != null) server.stop().join();
    }

    private void start(App app) {
        server = Server.start(app.route(HttpMethod.POST, "/double", Doubler.class)
                        .route(HttpMethod.GET, "/feed", Feed.class)
                        .route(HttpMethod.POST, "/sink", Sink.class),
                ServerConfig.builder().host("127.0.0.1").port(0).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    }

    private HttpResponse<String> post(String path, String type, String body, String... headers) throws Exception {
        HttpRequest.Builder request = request(path).header("content-type", type).POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void jacksonReadsAndWritesALinePerValue() throws Exception {
        start(App.create().install(new JsonModule(), new NdjsonModule(Json.class)));
        assertLinesInAndOut();
    }

    @Test
    void avajeReadsAndWritesALinePerValue() throws Exception {
        start(App.create().install(new dev.relism.flash.ext.avaje.jsonb.JsonModule(),
                new NdjsonModule(dev.relism.flash.ext.avaje.jsonb.Json.class)));
        assertLinesInAndOut();
    }

    private void assertLinesInAndOut() throws Exception {
        String body = "{\"name\":\"a\",\"quantity\":1}\r\n\n{\"name\":\"b\",\"quantity\":2}";
        for (String type : List.of("application/x-ndjson", "application/jsonl; charset=utf-8")) {
            HttpResponse<String> doubled = post("/double", type, body);
            assertEquals(200, doubled.statusCode());
            assertEquals("application/x-ndjson", doubled.headers().firstValue("content-type").orElseThrow());
            assertEquals("{\"name\":\"a\",\"quantity\":2}\n{\"name\":\"b\",\"quantity\":4}\n", doubled.body());
        }
        HttpResponse<String> sunk = post("/sink", "application/x-ndjson", body);
        assertEquals(200, sunk.statusCode());
        assertEquals("", sunk.body());
        assertEquals(2, Sink.read);
    }

    @Test
    void anotherContentTypeIs415AndABadLineBeforeAnyOutputIs400() throws Exception {
        start(App.create().install(new JsonModule(), new NdjsonModule(Json.class)));

        assertEquals(415, post("/double", "application/json", "{}").statusCode());
        HttpResponse<String> bad = post("/double", "application/x-ndjson", "{\"name\":\"a\",\"quantity\":1}\n{\"name\":");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("NDJSON line 2: "), bad.body());
    }

    /** After the first line has left, a failure can only cut the response off: the client sees no clean end. */
    @Test
    void aBadLineAfterOutputHasLeftCutsTheResponseOff() throws Exception {
        start(App.create().install(new JsonModule(), new NdjsonModule(Json.class)));

        assertThrows(IOException.class, () -> post("/double", "application/x-ndjson",
                "{\"name\":\"a\",\"quantity\":1}\nnot json\n", "x-flush", "yes"));
    }

    @Test
    void flushSendsALineBeforeTheHandlerReturns() throws Exception {
        start(App.create().install(new JsonModule(), new NdjsonModule(Json.class)));
        Feed.next = new CountDownLatch(1);

        HttpResponse<InputStream> feed = client.send(request("/feed").build(), HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(feed.body(), StandardCharsets.UTF_8))) {
            assertEquals("{\"name\":\"first\",\"quantity\":1}", lines.readLine());
            Feed.next.countDown();
            assertEquals("{\"name\":\"second\",\"quantity\":2}", lines.readLine());
            assertEquals(null, lines.readLine());
        }
    }

    @Test
    void aCodecThatPrettyPrintsIsRefusedBeforeItBreaksALine() throws Exception {
        start(App.create().install(new JsonModule(JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()),
                new NdjsonModule(Json.class)));

        HttpResponse<String> refused = post("/double", "application/x-ndjson", "{\"name\":\"a\",\"quantity\":1}\n");
        assertEquals(500, refused.statusCode());
    }

    @Test
    void theBootNamesWhatIsMissingOrWrong() {
        String unmodule = assertThrows(IllegalStateException.class, () -> start(App.create().install(new JsonModule())))
                .getMessage();
        assertTrue(unmodule.contains("Doubler is an NdjsonHandler: install new NdjsonModule(Json.class)"), unmodule);

        String uninstalled = assertThrows(IllegalStateException.class,
                () -> start(App.create().install(new NdjsonModule(Json.class)))).getMessage();
        assertTrue(uninstalled.contains("no " + Json.class.getName() + " is installed"), uninstalled);

        String xml = assertThrows(IllegalStateException.class, () -> start(App.create().add(Xml.class, new Xml())
                .install(new NdjsonModule(Xml.class)))).getMessage();
        assertTrue(xml.contains("NDJSON lines are JSON, and " + Xml.class.getName() + " writes application/xml"), xml);
    }

    /** A codec that is not JSON. */
    public static final class Xml implements Codec {
        @Override public Decoder decoder(Type type) { throw new UnsupportedOperationException(); }
        @Override public Encoder encoder(Type type) { throw new UnsupportedOperationException(); }
        @Override public Object write(Response response, Object value) { throw new UnsupportedOperationException(); }
        @Override public ContentType contentType() { return ContentType.XML; }
    }
}
