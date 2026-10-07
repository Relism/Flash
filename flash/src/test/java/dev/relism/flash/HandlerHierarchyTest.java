package dev.relism.flash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.http.Exchange;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HandlerHierarchyTest {

    @Test
    void initializationRunsOnceAndCannotRebindAHandlerToAnotherApp() {
        Services first = new Services();
        ProbeRequest handler = new ProbeRequest();

        handler.initialize(first);
        handler.initialize(first);

        assertEquals(1, handler.initialized);
        assertSame(first, handler.services);
        assertThrows(IllegalStateException.class, () -> handler.initialize(new Services()));
    }

    @Test
    void requestHandlersTurnTheirReturnValueIntoTheExchangeAnswer() throws Exception {
        Response response = new Response(200, ContentType.TEXT_PLAIN);
        Exchange exchange = new Exchange().reset(null, response, null);

        RequestHandler.of((request, current) -> "answer").prepare(exchange);

        assertSame(response, exchange.response());
        assertArrayEquals("answer".getBytes(StandardCharsets.UTF_8), response.getBody());

        Response replacement = new Response(201, ContentType.NONE);
        RequestHandler.of((request, current) -> replacement)
                .prepare(exchange.reset(null, response, null));
        assertSame(replacement, exchange.response());
    }

    @Test
    void duplexExecutionWaitsUntilPreparationHasFinished() throws Exception {
        MemoryDuplex transport = new MemoryDuplex();
        ProbeDuplex handler = new ProbeDuplex();
        Response response = new Response(200, ContentType.NONE);
        Exchange exchange = new Exchange().reset(null, response, transport);

        handler.prepare(exchange);

        assertTrue(handler.prepared);
        assertFalse(handler.executed);
        exchange.execute();
        assertTrue(handler.executed);
        assertSame(response, transport.boundResponse());
        assertTrue(transport.protocolSwitched());
        assertEquals("handled", transport.output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recoveryCancelsAPreparedDuplexProtocol() throws Exception {
        MemoryDuplex transport = new MemoryDuplex();
        ProbeDuplex handler = new ProbeDuplex();
        Response response = new Response(200, ContentType.TEXT_PLAIN);
        Exchange exchange = new Exchange().reset(null, response, transport);

        handler.prepare(exchange);
        exchange.recover("fallback");
        exchange.execute();

        assertFalse(handler.executed);
        assertArrayEquals("fallback".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void aDuplexHandlerDoesNotSwitchTheHttp1ConnectionByDefault() throws Exception {
        MemoryDuplex transport = new MemoryDuplex();
        Exchange exchange = new Exchange().reset(null, new Response(200, ContentType.NONE), transport);

        new DefaultDuplex().prepare(exchange);
        exchange.execute();

        assertFalse(transport.protocolSwitched());
    }

    private static final class ProbeRequest extends RequestHandler {
        int initialized;
        Services services;

        @Override protected void onInit() {
            initialized++;
            services = services();
        }

        @Override protected Object handle(Request request, Response response) {
            return null;
        }
    }

    private static final class ProbeDuplex extends DuplexHandler {
        boolean prepared;
        boolean executed;

        @Override protected void prepare(Request request, Response response) {
            prepared = true;
        }

        @Override protected boolean switchesProtocol(Request request) {
            return true;
        }

        @Override protected void handle(Request request, Response response, Duplex duplex) throws Exception {
            executed = true;
            duplex.write("handled".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class DefaultDuplex extends DuplexHandler {
        @Override protected void handle(Request request, Response response, Duplex duplex) {}
    }

    private static final class MemoryDuplex extends Duplex {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override public void write(byte[] bytes, int offset, int length) {
            output.write(bytes, offset, length);
        }

        @Override public void flush() {}
        @Override public int fill() { return -1; }
        @Override public byte[] array() { return new byte[0]; }
        @Override public int offset() { return 0; }
        @Override public void advance(int count) {}

        Response boundResponse() { return response(); }
        boolean protocolSwitched() { return switchesProtocol(); }
    }
}
