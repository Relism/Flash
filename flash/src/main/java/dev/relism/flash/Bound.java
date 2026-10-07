package dev.relism.flash;

/**
 * A framework seam, not an application API: something the app did not build through its
 * constructor gets the app's services here, once, at start. A handler base whose type arguments
 * name what it needs ({@code CodecRequestHandler}), or a service a module added already built (a codec
 * wrapping a configured mapper) and that looks up an optional collaborator. Applications take
 * services through their constructor.
 */
public interface Bound {

    void bind(Services services);
}
