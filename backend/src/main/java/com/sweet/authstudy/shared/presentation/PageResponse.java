package com.sweet.authstudy.shared.presentation;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(List<T> values, int page, int size) {
        int from = (int) Math.min((long) page * size, values.size());
        int to = Math.min(from + size, values.size());
        int pages = values.isEmpty() ? 0 : (values.size() + size - 1) / size;
        return new PageResponse<>(values.subList(from, to), page, size, values.size(), pages);
    }
}
