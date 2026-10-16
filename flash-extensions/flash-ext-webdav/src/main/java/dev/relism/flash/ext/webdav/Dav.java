package dev.relism.flash.ext.webdav;

import dev.relism.flash.routing.AnyMethod;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A WebDAV mount at its path, which ends in {@code /**}: one {@link DavHandler} answers every
 * method on the mount and below it.
 *
 * <pre>{@code
 * @Dav("/files/**")
 * final class Files extends StandaloneNioDavHandler {   // dev.relism.flash.ext.webdav.nio
 *     @Override protected Path root(Request req) { return Path.of("/srv/files"); }
 * }
 * }</pre>
 */
@AnyMethod
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Dav {
    String value();
}
