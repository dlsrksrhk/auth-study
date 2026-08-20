package com.sweet.authstudy.hr.position.domain;

import java.time.Instant;
import java.util.Objects;

public final class Position {

    private final Long id;
    private final long companyId;
    private final String code;
    private final Instant createdAt;
    private String name;
    private int level;
    private int displayOrder;
    private boolean active;
    private long version;
    private Instant updatedAt;

    private Position(
            Long id,
            long companyId,
            String code,
            String name,
            int level,
            int displayOrder,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.level = level;
        this.displayOrder = displayOrder;
        this.active = active;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Position create(
            long companyId,
            String code,
            String name,
            int level,
            int displayOrder,
            boolean active,
            Instant now) {
        return new Position(null, companyId, code, name, level, displayOrder, active, 0, now, now);
    }

    public static Position restore(
            Long id,
            long companyId,
            String code,
            String name,
            int level,
            int displayOrder,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Position(id, companyId, code, name, level, displayOrder, active, version, createdAt, updatedAt);
    }

    public void update(String name, int level, int displayOrder, boolean active, Instant now) {
        this.name = Objects.requireNonNull(name);
        this.level = level;
        this.displayOrder = displayOrder;
        this.active = active;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long id() {
        return id;
    }

    public long companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public int level() {
        return level;
    }

    public int displayOrder() {
        return displayOrder;
    }

    public boolean active() {
        return active;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
