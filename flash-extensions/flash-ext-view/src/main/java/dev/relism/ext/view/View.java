package dev.relism.ext.view;

import dev.relism.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative view binding for class-based handlers.
 *
 * <p>When {@code ViewExtension} is installed, handlers annotated with {@code @View}
 * receive an injected middleware that intercepts the handler's return value and
 * passes it to the {@link ViewEngine} for rendering. The rendered string replaces
 * the handler's return value as the response body, and {@link #contentType()} is
 * written to the {@code Content-Type} header.
 *
 * <h3>Return-value semantics</h3>
 * <ul>
 *   <li>Return a {@link Template} — overrides both the template name <em>and</em>
 *       the model dynamically (e.g. redirect to a different template on error).</li>
 *   <li>Return any other non-null value — used as the model; the template name
 *       comes from {@link #value()}.</li>
 *   <li>Return {@code null} — renders {@link #value()} with a {@code null} model.</li>
 * </ul>
 *
 * <pre>{@code
 * @Route(method = HttpMethod.GET, path = "/")
 * @View("home")
 * public class HomeHandler extends RequestHandler {
 *     private PostService posts;
 *     @Override protected void onInit() { posts = require(PostService.class); }
 *
 *     @Override public Object handle(Request req, Response res) {
 *         return Map.of("posts", posts.findAll());   // model → home template
 *     }
 * }
 *
 * // Dynamic template override via Template signal
 * @View("list")
 * public class ConditionalHandler extends RequestHandler {
 *     public Object handle(Request req, Response res) {
 *         if (something) return Template.of("error", Map.of("msg", "oops"));
 *         return data;   // uses "list" template
 *     }
 * }
 * }</pre>
 *
 * <p>The annotation is inspected via superclass traversal, so a base handler class
 * can declare the view template and all concrete subclasses inherit it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface View {

    /**
     * Template name or path passed to the {@link ViewEngine}.
     * The exact format is engine-specific (e.g. {@code "home"}, {@code "views/home.html"}).
     */
    String value();

    /**
     * {@code Content-Type} written to the response.
     * Defaults to {@link ContentType#TEXT_HTML}.
     */
    ContentType contentType() default ContentType.TEXT_HTML;

    /**
     * If {@code true}, instructs the {@link ViewEngine} to render only a named
     * fragment inside the template rather than the full page.
     * Useful for HTMX / partial-update patterns.
     */
    boolean fragment() default false;
}
