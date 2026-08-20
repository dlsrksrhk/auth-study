package com.sweet.authstudy.hr.user.domain;

import java.util.Optional;
import java.util.List;

import com.sweet.authstudy.hr.user.domain.UserStatus;

public interface UserRepository {

    HrUser save(HrUser user);

    Optional<HrUser> findById(long id);

    Optional<HrUser> findByCompanyIdAndCode(long companyId, String code);

    Optional<HrUser> findByCompanyIdAndEmployeeNumber(long companyId, String employeeNumber);

    List<HrUser> findAllByCompanyId(long companyId);

    UserPage search(long companyId, String search, UserStatus status, int page, int size, String sort);

    record UserPage(List<HrUser> content, long totalElements, int totalPages) {}
}
