package com.sweet.authstudy.hr.user.infrastructure;

import java.util.Optional;
import java.util.List;

import com.sweet.authstudy.hr.user.domain.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserJpaEntity u where u.id = :id")
    Optional<UserJpaEntity> findByIdForUpdate(@Param("id") long id);

    Optional<UserJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    Optional<UserJpaEntity> findByCompanyIdAndEmployeeNumber(long companyId, String employeeNumber);

    List<UserJpaEntity> findAllByCompanyId(long companyId);

    @Query("""
            select u from UserJpaEntity u
            where u.companyId = :companyId
              and (:status is null or u.status = :status)
              and (:search = ''
                or lower(u.name) like lower(concat('%', :search, '%'))
                or lower(u.code) like lower(concat('%', :search, '%'))
                or lower(u.employeeNumber) like lower(concat('%', :search, '%')))
            """)
    Page<UserJpaEntity> search(
            @Param("companyId") long companyId,
            @Param("search") String search,
            @Param("status") UserStatus status,
            Pageable pageable);
}
