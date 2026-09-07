package com.sweet.authstudy.hr.position.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface PositionJpaRepository extends JpaRepository<PositionJpaEntity, Long> {

    Optional<PositionJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    List<PositionJpaEntity> findAllByCompanyIdOrderByDisplayOrderAscCodeAsc(long companyId);

    @Query("""
            select p from PositionJpaEntity p
            where p.companyId = :companyId
              and (:active is null or p.active = :active)
              and (:search = '' or lower(p.code) like lower(concat('%', :search, '%'))
                or lower(p.name) like lower(concat('%', :search, '%')))
            """)
    Page<PositionJpaEntity> search(@Param("companyId") long companyId,
                                   @Param("search") String search, @Param("active") Boolean active, Pageable pageable);
}
