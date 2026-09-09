package dev.relism.flash.ext.validation;

import dev.relism.flash.ext.jackson.Json;
import dev.relism.flash.models.Request;

/**
 * The validation service. Resolve it with {@code require(Validation.class)}.
 *
 * <pre>{@code
 * CreateUser dto = validation.body(req, CreateUser.class);   // parse + verify
 * }</pre>
 *
 * <p>Constraints are compiled the first time a type is seen and cached in a {@link ClassValue},
 * which the JVM stores beside the class itself — no map lookup, no lock, and the entry is
 * collected with the class rather than pinning it. Every later request walks the compiled table.
 */
public final class Validation {

    private final ClassValue<Validator> validators = new ClassValue<>() {
        @Override protected Validator computeValue(Class<?> type) {
            return Validator.compile(type);
        }
    };

    /** Null when flash-ext-jackson is absent; only {@link #body} needs it. */
    private Json json;

    Validation() {}

    /** Called once at boot by {@link ValidationExtension}, after the service graph resolves. */
    void bindCodec(Json json) {
        this.json = json;
    }

    /**
     * Deserializes the request body into {@code type} and verifies its constraints.
     *
     * @throws dev.relism.flash.exceptions.HttpException 400 if the body is not valid JSON
     * @throws ValidationException 422 if it parses but violates a constraint
     */
    public <T> T body(Request request, Class<T> type) throws Exception {
        if (json == null)
            throw new IllegalStateException(
                    "Validation.body(...) needs a JSON codec — install JacksonExtension, "
                            + "or parse yourself and call validate(...)");
        T value = json.body(request, type);
        validators.get(type).verify(value);
        return value;
    }

    /**
     * Verifies an already-constructed value.
     *
     * @return {@code value}, so it can be used inline
     * @throws ValidationException 422 on the first type's worth of failures
     */
    public <T> T validate(T value) {
        validators.get(value.getClass()).verify(value);
        return value;
    }

    /**
     * The compiled constraints of {@code type}. Useful to pre-warm a hot DTO at boot, or to
     * check whether a type declares constraints at all.
     */
    public Validator forType(Class<?> type) {
        return validators.get(type);
    }
}
