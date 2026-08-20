package com.sweet.authstudy.shared.validation;

import java.util.Locale;
import java.util.regex.Pattern;

import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;

public final class BusinessCode {
    public static final String REGEXP = "[A-Za-z0-9][A-Za-z0-9_-]*";
    private static final Pattern PATTERN = Pattern.compile(REGEXP);

    private BusinessCode() {}

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 50 && PATTERN.matcher(trimmed).matches();
    }

    public static String normalize(String value) {
        if (!isValid(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Business code is invalid.");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
