package dev.relism.flash.ext.validation.protovalidate;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.validate.FieldPathElement;
import com.google.protobuf.Message;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.protobuf.Protobuf;
import dev.relism.flash.ext.protobuf.ProtobufValidation;
import dev.relism.flash.ext.validation.ValidationException;

import java.util.ArrayList;
import java.util.List;

/** Validates protobuf messages with rules declared in their descriptors: the engine {@code flash-ext-protobuf-java} asks for. */
public final class ProtovalidateValidation implements Module, ProtobufValidation {

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
        app.add(ProtobufValidation.class, this)
                .onStart(services -> {
                    if (services.find(Protobuf.class).isEmpty()) throw new IllegalStateException(
                            "ProtovalidateValidation checks the bodies the protobuf codec reads, and no ProtobufModule is installed");
                })
                .install(Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                        () -> docs -> docs.add(OpenApiContributor.class, ProtovalidateDocs.class)));
    }

    @Override
    public void verify(Object value) {
        if (!(value instanceof Message message)) return;

        ValidationResult result;
        try {
            result = validator.validate(message);
        } catch (build.buf.protovalidate.exceptions.ValidationException failure) {
            throw new IllegalStateException("Could not evaluate protobuf validation rules", failure);
        }
        if (result.isSuccess()) return;

        List<ValidationException.Violation> failures = new ArrayList<>(result.getViolations().size());
        for (build.buf.protovalidate.Violation violation : result.getViolations()) {
            build.buf.validate.Violation detail = violation.toProto();
            failures.add(new ValidationException.Violation(path(detail),
                    detail.hasMessage() ? detail.getMessage() : "is invalid"));
        }
        throw new ValidationException(failures);
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
