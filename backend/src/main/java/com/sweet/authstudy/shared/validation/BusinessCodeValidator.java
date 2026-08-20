package com.sweet.authstudy.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BusinessCodeValidator implements ConstraintValidator<ValidCode, String> {
    private boolean nullable;

    @Override
    public void initialize(ValidCode constraintAnnotation) {
        nullable = constraintAnnotation.nullable();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null ? nullable : BusinessCode.isValid(value);
    }
}
