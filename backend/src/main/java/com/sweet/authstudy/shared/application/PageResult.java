package com.sweet.authstudy.shared.application;

import java.util.List;

public record PageResult<T>(List<T> content, long totalElements, int totalPages) {
    public PageResult {
        content = List.copyOf(content);
    }
}
