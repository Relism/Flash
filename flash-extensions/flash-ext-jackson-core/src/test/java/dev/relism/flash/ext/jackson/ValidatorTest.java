package dev.relism.flash.ext.validation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    record CreateUser(
            @NotBlank @Size(max = 8) String name,
            @Email                   String email,
            @Min(18) @Max(120)       int    age,
            @NotNull                 String role) {}

    record Boxed(@Min(1) Integer count) {}

    record Sized(@NotEmpty List<String> tags, @Size(min = 2, max = 4) String code) {}

    record Patterned(@Pattern(regexp = "[a-z]+") String slug) {}

    record Plain(String anything) {}

    private static ValidationException failureOf(Object value) {
        return assertThrows(ValidationException.class, () -> Validator.compile(value.getClass()).verify(value));
    }

    @Test
    void aValidValuePasses() {
        assertDoesNotThrow(() ->
                Validator.compile(CreateUser.class).verify(new CreateUser("alice", "a@b.com", 30, "admin")));
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
        ValidationException failure = failureOf(new CreateUser("  ", "nope", 5, null));

        assertEquals(List.of("name", "email", "age", "role"),
                failure.violations().stream().map(ValidationException.Violation::field).toList());
    }

    @Test
    void violationsCarryFieldAndMessage() {
        ValidationException failure = failureOf(new CreateUser("alice", "a@b.com", 5, "admin"));

        assertEquals(1, failure.violations().size());
        assertEquals("age", failure.violations().get(0).field());
        assertEquals("must be between 18 and 120", failure.violations().get(0).message());
        assertEquals(422, failure.status());
        assertEquals("age must be between 18 and 120", failure.getMessage());
    }

    @Test
    void sizeCountsCharactersWithoutCopying() {
        assertEquals("name", failureOf(new CreateUser("far-too-long", "a@b.com", 30, "x"))
                .violations().get(0).field());
    }

    @Test
    void onlyNotNullRejectsNull() {
        // @Email, @Size and @Min all accept null per Jakarta semantics; @NotNull is the one that does not.
        ValidationException failure = failureOf(new CreateUser("alice", null, 30, null));

        assertEquals(List.of("role"),
                failure.violations().stream().map(ValidationException.Violation::field).toList());
    }

    @Test
    void boxedNumbersUseTheReferencePathAndTolerateNull() {
        assertDoesNotThrow(() -> Validator.compile(Boxed.class).verify(new Boxed(null)));
        assertEquals("count", failureOf(new Boxed(0)).violations().get(0).field());
    }

    @Test
    void sizeAppliesToCollectionsAndStrings() {
        assertDoesNotThrow(() -> Validator.compile(Sized.class).verify(new Sized(List.of("a"), "abc")));

        ValidationException failure = failureOf(new Sized(List.of(), "x"));
        assertEquals(List.of("tags", "code"),
                failure.violations().stream().map(ValidationException.Violation::field).toList());
    }

    @Test
    void patternIsAnchoredLikeJakarta() {
        assertDoesNotThrow(() -> Validator.compile(Patterned.class).verify(new Patterned("abc")));
        assertEquals("slug", failureOf(new Patterned("Abc1")).violations().get(0).field());
    }

    @Test
    void emailAcceptsPlausibleAddressesAndRejectsTypos() {
        assertDoesNotThrow(() ->
                Validator.compile(CreateUser.class).verify(new CreateUser("a", "first.last@sub.example.co", 20, "x")));

        for (String bad : List.of("no-at", "@leading.com", "trailing@", "two@@at.com", "no dots@x", "a@b")) {
            assertThrows(ValidationException.class,
                    () -> Validator.compile(CreateUser.class).verify(new CreateUser("a", bad, 20, "x")),
                    bad);
        }
    }

    @Test
    void aTypeWithNoConstraintsCompilesToANoOp() {
        Validator validator = Validator.compile(Plain.class);

        assertTrue(validator.isEmpty());
        assertDoesNotThrow(() -> validator.verify(new Plain(null)));
    }
}
