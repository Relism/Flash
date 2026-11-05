package dev.relism.flash.ext.validation.protovalidate;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.validate.FieldPathElement;
import com.google.protobuf.Message;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.ext.validation.ValidationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Validates protobuf messages with rules declared in their descriptors, whichever codec decoded them.
 * It checks only messages, so it lives beside an engine for everything else.
 */
public final class ProtovalidateValidation implements Module, Validation {

    private final Validator validator;

    public ProtovalidateValidation() {
        this(ValidatorFactory.newBuilder().build());
    }

    /** Uses a preconfigured, thread-safe Protovalidate validator. */
    public ProtovalidateValidation(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void install(App app) {
        app.install(Validation.module(this), Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> docs -> docs.add(OpenApiContributor.class, ProtovalidateDocs.class)));
    }

    @Override
    public boolean checks(Class<?> type) {
        return Message.class.isAssignableFrom(type);
    }

    /** A collection is checked element by element, each failure's path prefixed with its index. */
    @Override
    public void verify(Object value) {
        List<ValidationException.Violation> failures = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            int index = 0;
            for (Object item : items) check(item, "[" + index++ + "].", failures);
        } else {
            check(value, "", failures);
        }
        if (!failures.isEmpty()) throw new ValidationException(failures);
    }

    private void check(Object value, String prefix, List<ValidationException.Violation> failures) {
        if (!(value instanceof Message message)) return;
        ValidationResult result;
        try {
            result = validator.validate(message);
        } catch (build.buf.protovalidate.exceptions.ValidationException failure) {
            throw new IllegalStateException("Could not evaluate protobuf validation rules", failure);
        }
        for (build.buf.protovalidate.Violation violation : result.getViolations()) {
            build.buf.validate.Violation detail = violation.toProto();
            failures.add(new ValidationException.Violation(prefix + path(detail),
                    detail.hasMessage() ? detail.getMessage() : "is invalid"));
        }
    }

    private static String path(build.buf.validate.Violation violation) {
        if (!violation.hasField()) return "message";
        StringBuilder path = new StringBuilder();
        for (FieldPathElement element : violation.getField().getElementsList()) {
            if (element.hasFieldName()) {
                if (!path.isEmpty()) path.append('.');
                path.append(element.getFieldName());
            }
            switch (element.getSubscriptCase()) {
                case INDEX -> path.append('[').append(element.getIndex()).append(']');
                case BOOL_KEY -> path.append('[').append(element.getBoolKey()).append(']');
                case INT_KEY -> path.append('[').append(element.getIntKey()).append(']');
                case UINT_KEY -> path.append('[').append(Long.toUnsignedString(element.getUintKey())).append(']');
                case STRING_KEY -> path.append("['").append(element.getStringKey()).append("']");
                default -> { }
            }
        }
        return path.isEmpty() ? "message" : path.toString();
    }
}
