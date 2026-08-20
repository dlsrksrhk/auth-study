package com.sweet.authstudy.shared.presentation;

import java.util.Set;

import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;

public final class PageRules {
    private PageRules() {}

    public static void validate(int page, int size, String sort, Set<String> allowedSorts) {
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > 100 || offset + size > Integer.MAX_VALUE
                || sort == null || !allowedSorts.contains(sort)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid pagination or sort value.");
        }
    }
}
