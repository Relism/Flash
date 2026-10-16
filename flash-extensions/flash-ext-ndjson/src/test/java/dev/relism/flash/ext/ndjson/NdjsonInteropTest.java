package dev.relism.flash.ext.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.App;
import dev.relism.flash.ext.data.DataModule;
import dev.relism.flash.ext.data.core.ResourceRegistry;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxOutcome;
import dev.relism.flash.ext.data.core.TxSynchronization;
import dev.relism.flash.ext.data.jdbc.JdbcTxManager;
import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.limiter.Limit;
import dev.relism.flash.ext.limiter.LimiterModule;
import dev.relism.flash.ext.micrometer.MicrometerModule;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.ext.security.Authenticated;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.validation.jakarta.JakartaValidation;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.testing.FlashTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.transaction.Transactional;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** NDJSON installed with the extensions it meets in an app: one test per row of 8.1 and 8.2. */
class NdjsonInteropTest {

    /** Every value it reads, back, in a transaction. */
    @Transactional
    public static final class Import extends NdjsonHandler<Row, Row> {
        static final List<String> seen = new CopyOnWriteArrayList<>();
        private final Tx tx;

        public Import(Tx tx) { this.tx = tx; }

        @Override protected void handle(Request request, NdjsonInput<Row> in, NdjsonOutput<Row> out) throws Exception {
            ResourceRegistry.addSynchronization(new TxSynchronization() {
                @Override public void afterCompletion(TxOutcome outcome) { seen.add(outcome.name()); }
            });
            for (Row row; (row = in.next()) != null; ) {
                seen.add(row.name() + ":" + tx.isActive());
                out.send(row);
                if (row.name().equals("flush")) out.flush();
            }
        }
    }

    @Authenticated
    public static final class Mine extends NdjsonHandler<Void, Named> {
        @Override protected void handle(Request request, NdjsonInput<Void> in, NdjsonOutput<Named> out) throws Exception {
            out.send(new Named("mine"));
        }
    }

    @Limit(requests = 2, window = 60)
    public static final class Limited extends NdjsonHandler<Void, Row> {
        @Override protected void handle(Request request, NdjsonInput<Void> in, NdjsonOutput<Row> out) throws Exception {
            out.send(new Row("limited", 1));
        }
    }

    static final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    static App app(App app) {
        AuthenticationMechanism token = request -> "Bearer good".equals(request.header("authorization")) ? () -> "alice" : null;
        return app.install(new NdjsonModule(Json.class), new JakartaValidation(), new OpenApiModule(),
                        new DataModule(new JdbcTxManager(new H2())), new SecurityModule(), new LimiterModule(),
                        new MicrometerModule(registry))
                .add(AuthenticationMechanism.class, token)
                .route(HttpMethod.POST, "/import", Import.class)
                .route(HttpMethod.GET, "/mine", Mine.class)
                .route(HttpMethod.GET, "/limited", Limited.class)
                .get("/plain", (req, res) -> "plain");
    }

    @RegisterExtension
    static final FlashTest jackson = FlashTest.of(() -> app(App.create().install(new JsonModule())));

    @RegisterExtension
    static final FlashTest avaje = FlashTest.of(() -> App.create()
            .install(new dev.relism.flash.ext.avaje.jsonb.JsonModule(), new NdjsonModule(dev.relism.flash.ext.avaje.jsonb.Json.class),
                    new OpenApiModule())
            .route(HttpMethod.GET, "/mine", Mine.class));

    private static JsonNode document(FlashTest app) throws Exception {
        return new ObjectMapper().readTree(app.get("/openapi.json").expectStatus(200).body());
    }

    /** 8.1 ndjson → openapi, and 8.2 validation + ndjson: the line's schema carries its rules. */
    @Test
    void theDocumentDescribesEachLineWithItsRules() throws Exception {
        JsonNode post = document(jackson).at("/paths/~1import/post");
        String reference = post.at("/requestBody/content/application~1x-ndjson/schema/$ref").asText();
        assertEquals("#/components/schemas/Row", reference, post.toString());
        assertEquals("#/components/schemas/Row",
                post.at("/responses/200/content/application~1x-ndjson/schema/$ref").asText(), post.toString());
        JsonNode row = document(jackson).at("/components/schemas/Row");
        assertEquals(1, row.at("/properties/quantity/minimum").asInt(), row.toString());
        assertTrue(row.at("/required").toString().contains("name"), row.toString());
    }

    /** 8.2 validation + ndjson: a line that breaks a rule is 422 before anything was sent, and cuts the stream after. */
    @Test
    void aLineThatBreaksARuleIs422BeforeTheFirstByte() {
        jackson.request().header("content-type", "application/x-ndjson")
                .body("{\"name\":\"a\",\"quantity\":1}\n{\"name\":\"\",\"quantity\":0}\n").post("/import")
                .expectStatus(422).expectBodyContains("quantity");
    }

    /** 8.2 jackson, avaje + openapi + ndjson: wire names come from each codec's own annotations. */
    @Test
    void eachCodecNamesTheFieldsOfTheLineSchema() throws Exception {
        for (FlashTest app : List.of(jackson, avaje)) {
            JsonNode named = document(app).at("/components/schemas/Named/properties");
            assertNotNull(named.get("item_name"), named.toString());
            assertEquals("application/x-ndjson", document(app).at("/paths/~1mine/get/responses/200/content").fieldNames().next());
        }
    }

    /** 8.2 data + ndjson: the whole stream runs in one transaction, committed once. */
    @Test
    void theStreamRunsInOneTransaction() {
        Import.seen.clear();
        jackson.request().header("content-type", "application/x-ndjson")
                .body("{\"name\":\"a\",\"quantity\":1}\n{\"name\":\"flush\",\"quantity\":2}\n{\"name\":\"c\",\"quantity\":3}\n")
                .post("/import").expectStatus(200);
        assertEquals(List.of("a:true", "flush:true", "c:true", "COMMITTED"), Import.seen);
    }

    /** 8.2 security + ndjson: authentication is decided before the stream starts, so 401 is an ordinary answer. */
    @Test
    void anUnauthenticatedCallIsAnOrdinary401() {
        jackson.get("/mine").expectStatus(401);
        jackson.request().header("authorization", "Bearer good").get("/mine").expectStatus(200)
                .expectBody("{\"item_name\":\"mine\"}\n");
    }

    /** 8.2 limiter + ndjson: the limit applies, and the documented operation carries its headers and 429. */
    @Test
    void theLimiterLimitsAndDocumentsAnNdjsonRoute() throws Exception {
        JsonNode responses = document(jackson).at("/paths/~1limited/get/responses");
        assertTrue(responses.has("429"), responses.toString());
        assertTrue(responses.at("/200/headers").has("X-RateLimit-Limit"), responses.toString());

        for (int i = 0; i < 2; i++) jackson.get("/limited").expectStatus(200).expectHeader("X-RateLimit-Limit", "2");
        jackson.get("/limited").expectStatus(429);
    }

    /** 8.2 micrometer + ndjson: like every duplex route, NDJSON is not timed. */
    @Test
    void anNdjsonRouteIsNotTimed() {
        jackson.get("/plain").expectStatus(200);
        jackson.request().header("authorization", "Bearer good").get("/mine").expectStatus(200);
        assertNotNull(registry.find("http.server.requests").tag("uri", "/plain").timer());
        assertNull(registry.find("http.server.requests").tag("uri", "/mine").timer());
    }

    /** An in-memory H2 database, a fresh connection each time. */
    static final class H2 implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection("jdbc:h2:mem:ndjson;DB_CLOSE_DELAY=-1"); }
        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return getConnection(); }
        @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
