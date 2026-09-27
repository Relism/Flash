package dev.relism.flash.testing;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.Services;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs a real {@link App} on an OS-assigned loopback port for a test class or a single test, and
 * gives you a client pointed at it. The app is the one production runs, built by the same code:
 * nothing is reinstalled by hand, and a fake replaces exactly one service.
 *
 * <pre>{@code
 * class UserRoutesTest {
 *
 *     @RegisterExtension
 *     static FlashTest api = FlashTest.of(() -> Main.api(testDatabase()))
 *             .replace(Mailer.class, new FakeMailer());
 *
 *     @Test
 *     void listsUsers() {
 *         api.get("/api/users").expectStatus(200).expectBodyContains("alice");
 *         assertEquals(1, api.services().get(Users.class).count());
 *     }
 * }
 * }</pre>
 *
 * <p>Nothing is built or started until first use, so an app that reads a {@code @TempDir} sees it
 * populated, and one server can be wired from another in declaration order:
 *
 * <pre>{@code
 * @RegisterExtension static FlashTest auth = FlashTest.of(FakeIdentityProvider::app);
 * @RegisterExtension static FlashTest api  = FlashTest.of(() -> Main.api(auth.baseUri()));
 * }</pre>
 *
 * <p>A {@code static} field runs one server for the class; an instance field runs a fresh one for
 * every test — stock JUnit field semantics.
 */
public final class FlashTest implements BeforeAllCallback, AfterAllCallback,
                                        BeforeEachCallback, AfterEachCallback {

    private static final String LOOPBACK = "127.0.0.1";

    /** ponytail: a fixed short drain keeps teardown fast; add a knob if a test ever measures draining itself. */
    private static final int DRAIN_MS = 250;

    private final Supplier<App> application;
    private final Map<Class<?>, Object> replacements = new LinkedHashMap<>();
    private Consumer<ServerConfig.ServerConfigBuilder> config = builder -> { };

    private App app;
    private Server server;
    private HttpClient client;
    private URI baseUri;
    private boolean classScoped;

    private FlashTest(Supplier<App> application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    /** A harness for the app {@code application} builds, when the test first needs it. */
    public static FlashTest of(Supplier<App> application) {
        return new FlashTest(application);
    }

    /**
     * Customises the server: timeouts, HTTP/2 switches, limits. Host, port and the drain window are
     * the harness's, set after this runs; a listener or TLS setting is refused, because the harness
     * owns the one plaintext loopback listener it hands you a client for.
     */
    public FlashTest config(Consumer<ServerConfig.ServerConfigBuilder> config) {
        requireNotStarted("config(...)");
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /** Swaps every {@code type} the app adds, its modules included, for {@code instance}. */
    public <T> FlashTest replace(Class<T> type, T instance) {
        requireNotStarted("replace(...)");
        replacements.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(instance, "instance"));
        return this;
    }

    // ── Accessors (each starts the server if it is not running) ──────────────

    /** Base URI of the running server, e.g. {@code http://127.0.0.1:41307}. */
    public URI baseUri() { ensureStarted(); return baseUri; }

    public int port() { ensureStarted(); return server.port(); }

    /** The app's services, as built: assert on real state instead of rebuilding it. */
    public Services services() { ensureStarted(); return app.services(); }

    /** The client the harness issues requests with. */
    public HttpClient client() { ensureStarted(); return client; }

    // ── Requests ─────────────────────────────────────────────────────────────

    /** Starts a request with headers or a body; the HTTP verb sends it. */
    public FlashRequest request() { ensureStarted(); return new FlashRequest(this); }

    /** Sends {@code GET path} with no headers or body. */
    public FlashResponse get(String path) { return request().get(path); }

    /** Sends {@code DELETE path} with no headers or body. */
    public FlashResponse delete(String path) { return request().delete(path); }

    /** Opens a WebSocket to {@code path}; close it when done, usually with try-with-resources. */
    public FlashWebSocket ws(String path) {
        ensureStarted();
        return new FlashWebSocket(client,
                URI.create("ws://" + LOOPBACK + ':' + server.port() + FlashRequest.normalise(path)));
    }

    // ── JUnit lifecycle ──────────────────────────────────────────────────────
    // A static field receives class- AND method-level callbacks, so afterEach would otherwise
    // tear the server down after the first test; classScoped records which tier owns it.
    // Neither hook starts anything: a class holding several servers pays only for those used.

    @Override public void beforeAll(ExtensionContext context)  { classScoped = true; }
    @Override public void afterAll(ExtensionContext context)   { stop(); }
    @Override public void beforeEach(ExtensionContext context) { /* lazy */ }
    @Override public void afterEach(ExtensionContext context)  { if (!classScoped) stop(); }

    // ── Internals ────────────────────────────────────────────────────────────

    private void ensureStarted() {
        if (server == null) start();
    }

    @SuppressWarnings("unchecked")
    private void start() {
        ServerConfig.ServerConfigBuilder builder = ServerConfig.builder();
        config.accept(builder);
        ServerConfig built = builder.port(0).host(LOOPBACK).shutdownDrainTimeoutMs(DRAIN_MS).build();
        if (!built.getListeners().isEmpty())
            throw new IllegalStateException("FlashTest owns the listener: remove listener(...) from the config");
        if (built.getTls() != null)
            throw new IllegalStateException("FlashTest serves plaintext on loopback: remove tls(...) from the config");

        app = application.get();
        replacements.forEach((type, instance) -> app.replace((Class<Object>) type, instance));
        server = Server.start(app, built);
        baseUri = URI.create("http://" + LOOPBACK + ':' + server.port());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private void stop() {
        if (server == null) return;
        // The client holds keep-alive sockets open, and the server waits for the last one to close
        // or the drain window to pass: shut the client first. shutdownNow, because close() waits for
        // every operation, and a test that leaked an open WebSocket would hang teardown forever.
        client.shutdownNow();
        try {
            client.awaitTermination(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        server.stop().join();
        server = null;
        app = null;
        client = null;
        baseUri = null;
    }

    private void requireNotStarted(String what) {
        if (server != null)
            throw new IllegalStateException(what + " must be configured before the server starts");
    }
}
