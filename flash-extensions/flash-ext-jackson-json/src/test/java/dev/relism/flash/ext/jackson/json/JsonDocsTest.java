package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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

/** The document names a field the way Jackson writes it. */
class JsonDocsTest {

    public record Account(@JsonProperty("user_name") String name,
                          @JsonProperty(access = JsonProperty.Access.READ_ONLY) String id,
                          @JsonIgnore String secret) {}

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
        assertTrue(document.contains("\"readOnly\":true"), document);
        assertFalse(document.contains("\"secret\""), document);
    }
}
