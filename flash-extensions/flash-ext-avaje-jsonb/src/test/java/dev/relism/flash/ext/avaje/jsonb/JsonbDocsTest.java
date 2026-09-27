package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.App;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The document names a field the way Jsonb writes it. */
class JsonbDocsTest {

    public static final class Open extends JsonHandler<Account, Account> {
        @Override protected Account handle(Request request, Response response, Account account) { return account; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new OpenApiModule())
            .route(HttpMethod.POST, "/accounts", Open.class));

    @Test
    void aFieldIsDocumentedUnderItsWireNameAndAnIgnoredOneIsNot() {
        String document = app.get("/openapi.json").expectStatus(200).body();

        assertTrue(document.contains("\"user_name\""), document);
        assertFalse(document.contains("\"secret\""), document);
    }
}
