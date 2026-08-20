package com.sweet.authstudy.hr.department.domain;

import java.util.List;
import java.util.Optional;
import com.sweet.authstudy.shared.application.PageResult;

public interface DepartmentRepository {

    Department save(Department department);

    Optional<Department> findById(long id);

    Optional<Department> findByCompanyIdAndCode(long companyId, String code);

    List<Department> findAllByCompanyId(long companyId);

    PageResult<Department> search(
            long companyId, String search, DepartmentStatus status, int page, int size, String sort);

    boolean existsActiveChild(long parentDepartmentId);
}
