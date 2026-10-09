package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.App;
import dev.relism.flash.ext.data.DataModule;
import dev.relism.flash.ext.data.core.ResourceRegistry;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxOutcome;
import dev.relism.flash.ext.data.core.TxSynchronization;
import dev.relism.flash.http.AnsweredFailure;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import dev.relism.flash.sse.SseHandler;
import dev.relism.flash.sse.SseStream;
import dev.relism.flash.testing.FlashTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code @Transactional} on a handler class, whether the handler answers inside the chain or after it. */
class TransactionalTest {

    @Transactional
    public static final class Plain extends RequestHandler {
        private final Tx tx;

        public Plain(Tx tx) { this.tx = tx; }

        @Override protected Object handle(Request request, Response response) { return String.valueOf(tx.isActive()); }
    }

    @Transactional
    public static final class Events extends SseHandler {
        private final Tx tx;

        public Events(Tx tx) { this.tx = tx; }

        @Override protected void handle(Request request, SseStream stream) throws Exception { stream.send(String.valueOf(tx.isActive())); }
    }

    /** Reports its failure to the client itself, as a gRPC handler does with trailers. */
    @Transactional
    public static final class Told extends SseHandler {
        static final AtomicReference<TxOutcome> OUTCOME = new AtomicReference<>();

        @Override protected void handle(Request request, SseStream stream) throws Exception {
            ResourceRegistry.addSynchronization(new TxSynchronization() {
                @Override public void afterCompletion(TxOutcome outcome) { OUTCOME.set(outcome); }
            });
            stream.send("told");
            throw new AnsweredFailure(new IllegalStateException("told the client"));
        }
    }

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(new DataModule(new JdbcTxManager(new TestDataSource())))
            .route(HttpMethod.GET, "/plain", Plain.class)
            .route(HttpMethod.GET, "/events", Events.class)
            .route(HttpMethod.GET, "/told", Told.class));

    @Test
    void aHandlerRunsInATransaction() {
        app.request().get("/plain").expectStatus(200).expectBody("true");
    }

    @Test
    void aDuplexHandlerRunsInATransactionToo() {
        app.request().get("/events").expectStatus(200).expectBody("data: true\n\n");
    }

    @Test
    void anAnsweredFailureRollsBackAndStillAnswers() {
        app.request().get("/told").expectStatus(200).expectBody("data: told\n\n");
        assertEquals(TxOutcome.ROLLED_BACK, Told.OUTCOME.get());
    }
}
