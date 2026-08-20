package com.sweet.authstudy.hr.position.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PositionRequests {
    private PositionRequests() {}

    public record CreatePositionRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull Integer level,
            @NotNull Integer displayOrder) {}

    public record UpdatePositionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull Integer level,
            @NotNull Integer displayOrder,
            @NotNull Boolean active,
            @NotNull Long version) {}
}
