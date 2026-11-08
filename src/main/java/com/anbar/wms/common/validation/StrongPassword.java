package com.anbar.wms.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The annotated string must satisfy {@link PasswordPolicy}. {@code null} is invalid. */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {

    String message() default "does not meet the password policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordPolicy.violation(value)
                    .map(message -> {
                        context.disableDefaultConstraintViolation();
                        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                        return false;
                    })
                    .orElse(true);
        }
    }
}
