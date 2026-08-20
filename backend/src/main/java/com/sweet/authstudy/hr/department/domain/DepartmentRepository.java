package com.sweet.authstudy.hr.department.domain;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository {

    Department save(Department department);

    Optional<Department> findById(long id);

    Optional<Department> findByCompanyIdAndCode(long companyId, String code);

    List<Department> findAllByCompanyId(long companyId);

    boolean existsActiveChild(long parentDepartmentId);
}
