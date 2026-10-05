package dev.relism.flash.ext.validation.protovalidate;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.validate.FieldPath;
import build.buf.validate.FieldPathElement;
import com.google.protobuf.StringValue;
import dev.relism.flash.ext.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtovalidateValidationTest {

    @Test
    void translatesEveryProtovalidateFailureToFlashValidation() {
        build.buf.validate.Violation detail = build.buf.validate.Violation.newBuilder()
                .setField(FieldPath.newBuilder()
                        .addElements(FieldPathElement.newBuilder().setFieldName("users"))
                        .addElements(FieldPathElement.newBuilder().setIndex(2))
                        .addElements(FieldPathElement.newBuilder().setFieldName("email")))
                .setMessage("must be an email")
                .build();
        Validator validator = message -> new ValidationResult(List.of(violation(detail)));

        ValidationException failure = assertThrows(ValidationException.class,
                () -> new ProtovalidateValidation(validator).verify(StringValue.of("bad")));

        assertEquals(422, failure.status());
        assertEquals("users[2].email", failure.violations().getFirst().path());
        assertEquals("must be an email", failure.violations().getFirst().message());
    }

    @Test
    void ignoresValuesThatAreNotProtobufMessages() {
        Validator mustNotRun = message -> { throw new AssertionError("called"); };

        assertDoesNotThrow(() -> new ProtovalidateValidation(mustNotRun).verify("ordinary DTO"));
    }

    private static build.buf.protovalidate.Violation violation(build.buf.validate.Violation detail) {
        return new build.buf.protovalidate.Violation() {
            @Override public build.buf.validate.Violation toProto() { return detail; }
            @Override public FieldValue getFieldValue() { return null; }
            @Override public FieldValue getRuleValue() { return null; }
        };
    }
}
