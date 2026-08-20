package com.sweet.authstudy.hr.position.infrastructure;

import java.util.List;
import java.util.Optional;

import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import org.springframework.stereotype.Repository;

@Repository
public class PositionRepositoryAdapter implements PositionRepository {

    private final PositionJpaRepository repository;

    public PositionRepositoryAdapter(PositionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Position save(Position position) {
        PositionJpaEntity entity = position.id() == null
                ? PositionJpaEntity.from(position)
                : repository.findById(position.id())
                        .orElseThrow(() -> new IllegalStateException("Position does not exist."));
        if (position.id() != null) {
            entity.updateFrom(position);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Position> findByCompanyIdAndCode(long companyId, String code) {
        return repository.findByCompanyIdAndCodeIgnoreCase(companyId, code).map(PositionJpaEntity::toDomain);
    }

    @Override
    public List<Position> findAllByCompanyId(long companyId) {
        return repository.findAllByCompanyIdOrderByDisplayOrderAscCodeAsc(companyId).stream()
                .map(PositionJpaEntity::toDomain)
                .toList();
    }
}
