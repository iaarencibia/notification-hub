package io.github.iaarencibia.notifications.adapter.in.web;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A ceiling counted in characters, the way the database counts them.
 *
 * <p>{@code @Size} measures {@code CharSequence.length()}, UTF-16 code units, not characters: an
 * emoji is one character for a {@code VARCHAR} column and two for {@code length()}, so 512 emoji
 * would read as 1024 and be turned away over a value the column and the domain both accept.
 */
@Documented
@Constraint(validatedBy = CharacterLength.Validator.class)
// TYPE_USE lets this reach the keys and values inside a Map: type arguments, not members, and
// otherwise unannotatable.
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT, TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface CharacterLength {

    int max();

    String message() default "must not exceed {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Counts code points, so a character outside the basic plane counts once and not twice. */
    class Validator implements ConstraintValidator<CharacterLength, CharSequence> {

        private int max;

        @Override
        public void initialize(CharacterLength constraint) {
            this.max = constraint.max();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            // Absence is not this constraint's business; @NotBlank says whether it is allowed.
            if (value == null) {
                return true;
            }
            String text = value.toString();
            return text.codePointCount(0, text.length()) <= max;
        }
    }
}
