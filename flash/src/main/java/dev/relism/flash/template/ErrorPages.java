package dev.relism.flash.template;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Precompiled error page templates.
 * <p>
 * Static placeholders ({@code logo_img}) are baked in once at
 * class init. Dynamic placeholders (method, path, exception …) are filled per
 * call with a single allocation via {@link ByteTemplate#render}.
 */
@NoArgsConstructor
public final class ErrorPages {

    private static final ByteTemplate TEMPLATE_404;
    private static final ByteTemplate TEMPLATE_500;

    static {
        String logo = loadLogoImg();
        TEMPLATE_404 = bake("assets/html/default_404.html", logo);
        TEMPLATE_500 = bake("assets/html/default_exception.html", logo);
    }

    public static byte[] renderNotFound(Request request) {
        RequestLine rl = request.requestLine();
        return TEMPLATE_404.render(
                "method",    rl.getMethodName(),
                "path",      rl.getPath().toString(),
                "protocol",  rl.getProtocol().toString(),
                "timestamp", Instant.now().toString()
        );
    }

    public static byte[] renderException(Request request, Exception ex) {
        RequestLine rl = request.requestLine();
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return TEMPLATE_500.render(
                "method",            rl.getMethodName(),
                "path",              rl.getPath().toString(),
                "protocol",          rl.getProtocol().toString(),
                "exception_type",    ex.getClass().getName(),
                "exception_message", ex.getMessage() != null ? ex.getMessage() : "",
                "stacktrace",        sw.toString(),
                "timestamp",         Instant.now().toString()
        );
    }

    // --- init helpers ---

    private static ByteTemplate bake(String resource, String logoHtml) {
        String raw = load(resource)
                .replace("{{logo_img}}", logoHtml);
        return new ByteTemplate(raw);
    }

    private static String load(String resource) {
        try (InputStream is = ErrorPages.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new RuntimeException("Missing resource: " + resource);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load: " + resource, e);
        }
    }

    private static String loadLogoImg() {
        try (InputStream is = ErrorPages.class.getClassLoader().getResourceAsStream("assets/logo.png")) {
            if (is == null) return "";
            String b64 = Base64.getEncoder().encodeToString(is.readAllBytes());
            return "<img class=\"footer-logo\" src=\"data:image/png;base64," + b64 + "\" alt=\"Flash\">";
        } catch (IOException e) {
            return "";
        }
    }
}
