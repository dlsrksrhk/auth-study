package com.sweet.authstudy.hr.position.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface PositionJpaRepository extends JpaRepository<PositionJpaEntity, Long> {

    Optional<PositionJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    List<PositionJpaEntity> findAllByCompanyIdOrderByDisplayOrderAscCodeAsc(long companyId);
}
