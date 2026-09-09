package dev.relism.flash.testing;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashApplication;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.extension.FlashContext;
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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Boots a real {@link FlashApp} on an OS-assigned port for the duration of a test class or a
 * single test, and gives you a client pointed at it.
 *
 * <pre>{@code
 * class UserRoutesTest {
 *
 *     @RegisterExtension
 *     static FlashTest app = FlashTest.of(new BlogApp())
 *             .mock(UserService.class, new InMemoryUserService());
 *
 *     @Test
 *     void listsUsers() {
 *         app.get("/api/users")
 *            .expectStatus(200)
 *            .expectBodyContains("alice");
 *     }
 * }
 * }</pre>
 *
 * <h3>More than one server</h3>
 * Because this is an ordinary object in a field rather than an annotation, a test class can
 * hold as many as it needs, and one can be wired from another with plain Java. Startup is lazy
 * — reading {@code baseUri()} boots that server on the spot — so declaration order does the
 * wiring, with no reliance on JUnit's extension ordering:
 *
 * <pre>{@code
 * @RegisterExtension static FlashTest auth = FlashTest.of(new FakeOidcApp());
 * @RegisterExtension static FlashTest api  = FlashTest.of(new BlogApp(auth.baseUri()));
 * }</pre>
 *
 * <h3>Scope</h3>
 * A {@code static} field boots once for the class; a non-static field boots a fresh app for
 * every test. That is stock JUnit field semantics — the isolation switch is the keyword.
 *
 * <h3>Replacing services</h3>
 * {@link #mock} installs its replacements as the last extension, after everything the
 * application and its extensions declare, so a fake always wins. Any object will do — a
 * hand-written fake or a Mockito mock you created; this module depends on no mocking library.
 *
 * @see FlashRequest
 * @see FlashResponse
 */
public final class FlashTest implements BeforeAllCallback, AfterAllCallback,
                                        BeforeEachCallback, AfterEachCallback {

    private static final String LOOPBACK = "127.0.0.1";

    /**
     * ponytail: the harness pins the drain window so teardown is fast — the default 15s would
     * be paid on every class. Add a profile escape hatch if a test ever needs to exercise
     * draining itself.
     */
    private static final int DRAIN_MS = 250;

    private final FlashApplication application;
    private final Map<Class<?>, Object> overrides = new LinkedHashMap<>();
    private Consumer<FlashConfiguration.FlashConfigurationBuilder> profile = builder -> { };

    private FlashApp app;
    private HttpClient client;
    private URI baseUri;
    private boolean classScoped;

    private FlashTest(FlashApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    /**
     * Creates a harness for {@code application}. Nothing is bound or started until first use.
     *
     * <p>{@link FlashApplication} is a functional interface, so a lambda works as well as a
     * named class: {@code FlashTest.of(app -> app.get("/ping", (req, res) -> "pong"))}.
     */
    public static FlashTest of(FlashApplication application) {
        return new FlashTest(application);
    }

    /**
     * Customises the {@link FlashConfiguration} this server runs with — timeouts, HTTP/2
     * switches, buffer sizes.
     *
     * <p>The harness stamps host, port and the shutdown drain window <em>after</em> this runs,
     * so a profile cannot break it. Setting {@code listener(...)} or {@code tls(...)} is
     * rejected: the harness owns the single plaintext loopback listener it hands you a client
     * for.
     */
    public FlashTest profile(Consumer<FlashConfiguration.FlashConfigurationBuilder> profile) {
        requireNotStarted("profile(...)");
        this.profile = Objects.requireNonNull(profile, "profile");
        return this;
    }

    /**
     * Replaces the service bound to {@code type} with {@code instance} for this server.
     *
     * <p>Wins over anything the application or its extensions declare, including services
     * provided by an installed {@code FlashExtension}.
     *
     * <p>ponytail: overrides are applied to the app's root context. A service declared inside
     * a {@code mount(...)} scope's child context shadows the root and is therefore not
     * reachable — add child-context targeting if that ever comes up.
     */
    public <T> FlashTest mock(Class<T> type, T instance) {
        requireNotStarted("mock(...)");
        overrides.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(instance, "instance"));
        return this;
    }

    // ── Accessors (each boots the server if it is not running) ────────────────

    /** Base URI of the running server, e.g. {@code http://127.0.0.1:41307}. */
    public URI baseUri() { ensureStarted(); return baseUri; }

    /** OS-assigned port of the running server. */
    public int port() { ensureStarted(); return app.port(); }

    /** The running app — escape hatch for assertions the harness does not cover. */
    public FlashApp app() { ensureStarted(); return app; }

    /** The client the harness issues requests with. */
    public HttpClient client() { ensureStarted(); return client; }

    // ── Requests ─────────────────────────────────────────────────────────────

    /** Starts a request with headers or a body; the HTTP verb sends it. */
    public FlashRequest request() { ensureStarted(); return new FlashRequest(this); }

    /** Sends {@code GET path} with no headers or body. */
    public FlashResponse get(String path) { return request().get(path); }

    /** Sends {@code DELETE path} with no headers or body. */
    public FlashResponse delete(String path) { return request().delete(path); }

    /**
     * Opens a WebSocket to {@code path} on this server. Close it when done — a
     * try-with-resources block is the usual shape.
     */
    public FlashWebSocket ws(String path) {
        ensureStarted();
        return new FlashWebSocket(client,
                URI.create("ws://" + LOOPBACK + ':' + app.port() + FlashRequest.normalise(path)));
    }

    // ── JUnit lifecycle ──────────────────────────────────────────────────────
    // A static field receives class- AND method-level callbacks, so afterEach would otherwise
    // tear the server down after the first test. classScoped records which tier owns it.
    //
    // Neither hook starts anything: booting stays lazy, so a class holding several servers
    // only pays for the ones a test actually touches, and an application whose configure()
    // reads @TempDir sees it populated rather than null.

    @Override public void beforeAll(ExtensionContext context)  { classScoped = true; }
    @Override public void afterAll(ExtensionContext context)   { stop(); }
    @Override public void beforeEach(ExtensionContext context) { /* lazy */ }
    @Override public void afterEach(ExtensionContext context)  { if (!classScoped) stop(); }

    // ── Internals ────────────────────────────────────────────────────────────

    private void ensureStarted() {
        if (app == null) start();
    }

    private void start() {
        FlashConfiguration.FlashConfigurationBuilder builder = FlashConfiguration.builder();
        profile.accept(builder);
        FlashConfiguration config = builder
                .port(0)
                .host(LOOPBACK)
                .shutdownDrainTimeoutMs(DRAIN_MS)
                .build();
        rejectListenerOverrides(config);

        FlashApp starting = FlashApp.create(config).apply(application);
        if (!overrides.isEmpty())
            starting.install((registrar, ctx) -> applyOverrides(ctx));
        starting.start();

        app = starting;
        baseUri = URI.create("http://" + LOOPBACK + ':' + starting.port());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * {@code listeners} is a lombok {@code @Singular} field, so it can only be inspected after
     * the build — hence build-then-check rather than a guard on the builder.
     */
    private static void rejectListenerOverrides(FlashConfiguration config) {
        if (!config.getListeners().isEmpty())
            throw new IllegalStateException(
                    "FlashTest owns the listener — remove listener(...) from the profile");
        if (config.getTls() != null)
            throw new IllegalStateException(
                    "FlashTest serves plaintext on loopback — remove tls(...) from the profile");
    }

    @SuppressWarnings("unchecked")
    private void applyOverrides(FlashContext ctx) {
        overrides.forEach((type, instance) -> ctx.override((Class<Object>) type, instance));
    }

    private void stop() {
        if (app == null) return;
        // Order matters: the client holds keep-alive sockets open, and ServerLifecycle.stop()
        // spins until the last one closes or the drain window expires. shutdownNow rather than
        // close(), which blocks until every operation completes — a test that leaked an open
        // WebSocket would hang teardown forever.
        client.shutdownNow();
        try {
            client.awaitTermination(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        app.stop().join();
        app = null;
        client = null;
        baseUri = null;
    }

    private void requireNotStarted(String what) {
        if (app != null)
            throw new IllegalStateException(what + " must be configured before the server starts");
    }
}
