package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each UI renders one page: its own bundle, its own options, the document it reads. */
class UiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String page(Ui<?> ui) {
        return new String(ui.page("/openapi.json", "My API", JSON), StandardCharsets.UTF_8);
    }

    @Test
    void swagger_mounts_its_bundle_on_the_spec() {
        String page = page(Ui.swagger());

        assertTrue(page.contains("<title>My API</title>"));
        assertTrue(page.contains("https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.33.0/swagger-ui.css"));
        assertTrue(page.contains("https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.33.0/swagger-ui-bundle.js"));
        assertTrue(page.contains("SwaggerUIBundle({"));
        assertTrue(page.contains("\"dom_id\":\"#ui\""));
        assertTrue(page.contains("\"deepLinking\":true"));
        assertTrue(page.contains("\"url\":\"/openapi.json\""));
        // The preset it used to name lives in another bundle, and BaseLayout never needed it.
        assertFalse(page.contains("StandalonePreset"));
    }

    @Test
    void swagger_options_carry_their_upstream_names() {
        String page = page(Ui.swagger()
                .docExpansion(Ui.Swagger.Expand.NONE)
                .modelsExpandDepth(-1)
                .filter(true)
                .tryItOut(true)
                .persistAuthorization(true)
                .syntaxTheme(Ui.Swagger.Syntax.TOMORROW_NIGHT)
                .sortAlphabetically(true));

        assertTrue(page.contains("\"docExpansion\":\"none\""));
        assertTrue(page.contains("\"defaultModelsExpandDepth\":-1"));
        assertTrue(page.contains("\"filter\":true"));
        assertTrue(page.contains("\"tryItOutEnabled\":true"));
        assertTrue(page.contains("\"persistAuthorization\":true"));
        assertTrue(page.contains("\"syntaxHighlight\":{\"theme\":\"tomorrow-night\"}"));
        assertTrue(page.contains("\"tagsSorter\":\"alpha\""));
        assertTrue(page.contains("\"operationsSorter\":\"alpha\""));
    }

    /** Turning an option off leaves the bundle's own default, rather than writing a null over it. */
    @Test
    void an_option_set_back_to_its_default_is_dropped() {
        String page = page(Ui.swagger().sortAlphabetically(true).sortAlphabetically(false));

        assertFalse(page.contains("tagsSorter"));
        assertFalse(page.contains("operationsSorter"));
    }

    @Test
    void redoc_initializes_itself_on_the_configuration_it_is_given() {
        String page = page(Ui.redoc()
                .hideDownloadButton(true)
                .disableSearch(true)
                .requiredPropsFirst(true)
                .sortPropsAlphabetically(true)
                .jsonSampleExpandLevel(3)
                .hideSchemaTitles(true)
                .pathInMiddlePanel(true)
                .hideHostname(true)
                .nativeScrollbars(true)
                .menuToggle(false));

        assertTrue(page.contains("https://cdn.jsdelivr.net/npm/redoc@2.5.4/bundles/redoc.standalone.js"));
        assertTrue(page.contains("Redoc.init(c.url,c,document.getElementById('ui'))"));
        assertTrue(page.contains("\"hideDownloadButton\":true"));
        assertTrue(page.contains("\"disableSearch\":true"));
        assertTrue(page.contains("\"requiredPropsFirst\":true"));
        assertTrue(page.contains("\"sortPropsAlphabetically\":true"));
        assertTrue(page.contains("\"jsonSampleExpandLevel\":3"));
        assertTrue(page.contains("\"hideSchemaTitles\":true"));
        assertTrue(page.contains("\"pathInMiddlePanel\":true"));
        assertTrue(page.contains("\"hideHostname\":true"));
        assertTrue(page.contains("\"nativeScrollbars\":true"));
        assertTrue(page.contains("\"menuToggle\":false"));
    }

    @Test
    void scalar_writes_camel_cased_theme_names_and_hides_its_own_toolbar() {
        String page = page(Ui.scalar()
                .theme(Ui.Scalar.Theme.BLUE_PLANET)
                .layout(Ui.Scalar.Layout.CLASSIC)
                .darkMode(true)
                .hideDarkModeToggle(true)
                .hideModels(true)
                .hideSearch(true)
                .hideTestRequestButton(true)
                .hideClientButton(true)
                .showSidebar(false)
                .defaultOpenAllTags(true)
                .sortOperationsBy(Ui.Scalar.Sort.METHOD));

        assertTrue(page.contains("https://cdn.jsdelivr.net/npm/@scalar/api-reference@1.71.0"));
        assertTrue(page.contains("Scalar.createApiReference('#ui',{"));
        assertTrue(page.contains("\"showDeveloperTools\":\"never\""));
        assertTrue(page.contains("\"theme\":\"bluePlanet\""));
        assertTrue(page.contains("\"layout\":\"classic\""));
        assertTrue(page.contains("\"darkMode\":true"));
        assertTrue(page.contains("\"hideDarkModeToggle\":true"));
        assertTrue(page.contains("\"hideModels\":true"));
        assertTrue(page.contains("\"hideSearch\":true"));
        assertTrue(page.contains("\"hideTestRequestButton\":true"));
        assertTrue(page.contains("\"hideClientButton\":true"));
        assertTrue(page.contains("\"showSidebar\":false"));
        assertTrue(page.contains("\"defaultOpenAllTags\":true"));
        assertTrue(page.contains("\"operationsSorter\":\"method\""));
    }

    @Test
    void every_theme_name_reaches_the_configuration() {
        for (Ui.Scalar.Theme theme : Ui.Scalar.Theme.values()) {
            String page = page(Ui.scalar().theme(theme));
            assertTrue(page.matches("(?s).*\"theme\":\"[a-zA-Z]+\".*"), theme + " wrote no theme");
        }
        assertTrue(page(Ui.scalar().theme(Ui.Scalar.Theme.DEEP_SPACE)).contains("\"theme\":\"deepSpace\""));
    }

    @Test
    void a_custom_stylesheet_and_a_mirror_replace_the_defaults() {
        String page = page(Ui.redoc().customCss("body{color:red}").cdn("https://assets.example/redoc/"));

        assertTrue(page.contains("<style>body{margin:0}body{color:red}</style>"));
        assertTrue(page.contains("https://assets.example/redoc/bundles/redoc.standalone.js"));
        assertFalse(page.contains("jsdelivr"));
    }

    /** What this class does not name still reaches the bundle, nested objects included. */
    @Test
    void an_unnamed_option_passes_through() {
        String page = page(Ui.redoc().option("theme", java.util.Map.of("colors", java.util.Map.of("primary", "#0a0"))));

        assertTrue(page.contains("\"theme\":{\"colors\":{\"primary\":\"#0a0\"}}"));
    }

    @Test
    void none_is_the_absence_of_a_page() {
        assertEquals(null, Ui.none());
    }
}
