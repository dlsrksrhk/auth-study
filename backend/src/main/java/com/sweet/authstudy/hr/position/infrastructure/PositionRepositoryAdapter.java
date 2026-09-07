package com.sweet.authstudy.hr.position.infrastructure;

import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    @Override
    public PageResult<Position> search(
            long companyId, String search, Boolean active, int page, int size, String sort) {
        var result = repository.search(companyId, search, active,
                PageRequest.of(page, size, stableSort(sort, "code")));
        return new PageResult<>(result.getContent().stream().map(PositionJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private Sort stableSort(String requested, String tieBreaker) {
        Sort sort = Sort.by(requested);
        return requested.equals(tieBreaker) ? sort : sort.and(Sort.by(tieBreaker));
    }
}
