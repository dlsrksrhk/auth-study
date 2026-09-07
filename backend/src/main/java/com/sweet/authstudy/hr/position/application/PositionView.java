package com.sweet.authstudy.hr.position.application;

import com.sweet.authstudy.hr.position.domain.Position;

import java.time.Instant;

public record PositionView(
        long id,
        long companyId,
        String code,
        String name,
        int level,
        int displayOrder,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static PositionView from(Position position) {
        return new PositionView(
                position.id(),
                position.companyId(),
                position.code(),
                position.name(),
                position.level(),
                position.displayOrder(),
                position.active(),
                position.version(),
                position.createdAt(),
                position.updatedAt());
    }
}
