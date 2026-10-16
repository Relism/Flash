package dev.relism.flash.ext.webdav;

import dev.relism.flash.ext.webdav.nio.StandaloneNioDavHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import dev.relism.flash.App;
import dev.relism.flash.ext.data.DataModule;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.jdbc.JdbcTxManager;
import dev.relism.flash.ext.limiter.Limit;
import dev.relism.flash.ext.limiter.LimiterModule;
import dev.relism.flash.ext.micrometer.MicrometerModule;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.ext.security.Authenticated;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.basic.BasicAuth;
import dev.relism.flash.ext.security.form.PasswordEncoder;
import dev.relism.flash.ext.security.form.PasswordStore;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.routing.Cors;
import dev.relism.flash.testing.FlashTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** WebDAV installed with the extensions it meets in an app: one test per row of 8.1 and 8.2. */
class DavInteropTest {

    static final Path ROOT = root();

    /** Behind Basic, rate-limited, in a transaction: each extension has something to act on. */
    @Dav("/apps/team/files/**")
    @Authenticated
    @Transactional
    @Limit(requests = 50, window = 60)
    public static final class Files extends StandaloneNioDavHandler {
        static final List<Boolean> transactional = new CopyOnWriteArrayList<>();
        private final Tx tx;

        public Files(Tx tx) {
            this.tx = tx;
        }

        @Override protected Path root(Request req) { return ROOT; }

        @Override protected Optional<DavNode> stat(Request req, DavPath path) throws IOException {
            transactional.add(tx.isActive());
            return super.stat(req, path);
        }
    }

    @Limit(requests = 2, window = 60)
    public static final class Tight extends StandaloneNioDavHandler {
        @Override protected Path root(Request req) { return ROOT; }
    }

    static final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    /** No hashing cost in a test: the cache in front of it is BasicAuth's own test. */
    static final PasswordEncoder plain = new PasswordEncoder() {
        @Override public String encode(CharSequence password) { return "plain:" + password; }
        @Override public boolean matches(CharSequence password, String encoded) { return encoded.equals("plain:" + password); }
    };

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> {
        PasswordStore accounts = name -> name.equals("alice") ? new PasswordStore.Account(() -> "alice", "plain:s3cret") : null;
        return App.create()
                .use(Cors.allow("https://app.example"))
                .install(new SecurityModule(), new BasicAuth(accounts).passwordEncoder(plain), new DavModule(),
                        new OpenApiModule(), new MicrometerModule(registry), new LimiterModule(),
                        new DataModule(new JdbcTxManager(new H2())))
                .route(HttpMethod.GET, "/healthz", (req, res) -> "ok")
                .route(HttpMethod.OPTIONS, "/apps", (req, res) -> { res.header("x-own", "yes"); return null; })
                .scan("dev.relism.flash.ext.webdav")
                .any("/tight/**", new Tight());
    });

    private static String alice() {
        return "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
    }

    /** 8.1 webdav → security-basic: Windows' OPTIONS above the mount needs no credentials; the mount does. */
    @Test
    void optionsAboveTheMountIsPublicAndTheMountIsNot() {
        app.request().options("/").expectStatus(200).expectHeader("DAV", "1, 2").expectHeader("MS-Author-Via", "DAV");
        app.request().options("/apps/team").expectStatus(200).expectHeader("DAV", "1, 2");
        app.request().options("/apps").expectStatus(200).expectHeader("x-own", "yes"); // the app's own route wins
        app.request().options("/apps/team/files/").expectStatus(401)
                .expectHeader("WWW-Authenticate", "Basic realm=\"Flash\", charset=\"UTF-8\"");
        app.request().header("Authorization", alice()).options("/apps/team/files/").expectStatus(200).expectHeader("DAV", "1, 2");
        app.request().header("Origin", "https://app.example").header("Access-Control-Request-Method", "PROPFIND")
                .options("/").expectStatus(204); // a CORS preflight is CORS's
        app.request().options("/elsewhere").expectStatus(404);
    }

    /** 8.1 webdav → openapi: a route for every method has no place in an OpenAPI 3.0 document. */
    @Test
    void theMountIsNotInTheDocument() {
        String document = app.get("/openapi.json").expectStatus(200).body();
        assertTrue(document.contains("/healthz") && !document.contains("files") && !document.contains("tight"), document);
    }

    /** 8.1 webdav → micrometer: measured like any route, its own methods under one tag. */
    @Test
    void standardMethodsKeepTheirTagAndTheRestShareOther() {
        app.request().header("Authorization", alice()).header("Depth", "0").send("PROPFIND", "/apps/team/files/").expectStatus(207);
        app.request().header("Authorization", alice()).get("/apps/team/files/").expectStatus(200);
        assertNotNull(registry.find("http.server.requests").tags("method", "_OTHER", "uri", "/apps/team/files/**", "status", "207").timer());
        assertNotNull(registry.find("http.server.requests").tags("method", "GET", "uri", "/apps/team/files/**").timer());
    }

    /** 8.2 data + webdav: one transaction per request, the streamed PROPFIND answer included. */
    @Test
    void eachRequestRunsInATransaction() {
        Files.transactional.clear();
        app.request().header("Authorization", alice()).body("x").put("/apps/team/files/t.txt").expectStatus(201);
        app.request().header("Authorization", alice()).header("Depth", "1").send("PROPFIND", "/apps/team/files/").expectStatus(207);
        assertTrue(!Files.transactional.isEmpty() && !Files.transactional.contains(false), Files.transactional.toString());
    }

    /** 8.2 limiter + webdav: a limit applies to every method of the mount; a low one stops a chatty client. */
    @Test
    void aLimitCoversEveryMethod() {
        app.request().header("Depth", "0").send("PROPFIND", "/tight/").expectStatus(207);
        app.request().send("OPTIONS", "/tight/").expectStatus(200);
        assertEquals(429, app.request().header("Depth", "0").send("PROPFIND", "/tight/").status());
    }

    private static Path root() {
        try {
            return java.nio.file.Files.createDirectories(Jimfs.newFileSystem(Configuration.unix().toBuilder()
                    .setAttributeViews("basic", "user").build()).getPath("/interop"));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class H2 implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection("jdbc:h2:mem:dav;DB_CLOSE_DELAY=-1"); }
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
