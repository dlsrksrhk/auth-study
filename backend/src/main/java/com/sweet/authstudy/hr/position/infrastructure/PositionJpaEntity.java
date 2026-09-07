package com.sweet.authstudy.hr.position.infrastructure;

import com.sweet.authstudy.hr.position.domain.Position;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "positions")
class PositionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private long companyId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int level;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PositionJpaEntity() {
    }

    private PositionJpaEntity(Position position) {
        this.companyId = position.companyId();
        this.code = position.code();
        this.name = position.name();
        this.level = position.level();
        this.displayOrder = position.displayOrder();
        this.active = position.active();
        this.createdAt = position.createdAt();
        this.updatedAt = position.updatedAt();
    }

    static PositionJpaEntity from(Position position) {
        return new PositionJpaEntity(position);
    }

    void updateFrom(Position position) {
        this.name = position.name();
        this.level = position.level();
        this.displayOrder = position.displayOrder();
        this.active = position.active();
        this.updatedAt = position.updatedAt();
    }

    Position toDomain() {
        return Position.restore(
                id,
                companyId,
                code,
                name,
                level,
                displayOrder,
                active,
                version,
                createdAt,
                updatedAt);
    }
}
