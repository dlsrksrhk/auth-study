package com.sweet.authstudy.hr.position.application;

import java.time.Instant;

import com.sweet.authstudy.hr.position.domain.Position;

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
