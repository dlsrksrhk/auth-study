package com.sweet.authstudy.hr.user.domain;

import java.util.Optional;

public interface UserRepository {

    HrUser save(HrUser user);

    Optional<HrUser> findById(long id);

    Optional<HrUser> findByCompanyIdAndCode(long companyId, String code);

    Optional<HrUser> findByCompanyIdAndEmployeeNumber(long companyId, String employeeNumber);
}
