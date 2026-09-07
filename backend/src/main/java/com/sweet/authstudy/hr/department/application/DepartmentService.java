package com.sweet.authstudy.hr.department.application;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.audit.application.AuditFailurePlan;
import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.audit.application.AuditedTransactionExecutor;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.shared.application.PageResult;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.sweet.authstudy.hr.department.application.DepartmentCommands.*;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final CompanyRepository companyRepository;
    private final MembershipRepository membershipRepository;
    private final Clock clock;
    private final TenantGuard tenantGuard;
    private final AuditService auditService;
    private final AuditedTransactionExecutor auditedTransactions;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            CompanyRepository companyRepository,
            MembershipRepository membershipRepository, TenantGuard tenantGuard,
            AuditService auditService, AuditedTransactionExecutor auditedTransactions, Clock clock) {
        this.departmentRepository = departmentRepository;
        this.companyRepository = companyRepository;
        this.membershipRepository = membershipRepository;
        this.tenantGuard = tenantGuard;
        this.auditService = auditService;
        this.auditedTransactions = auditedTransactions;
        this.clock = clock;
    }

    @Transactional
    public DepartmentView create(AuthenticatedAccount actor, CreateDepartmentCommand command) {
        if (command == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Department data is required.");
        }
        Company company = findLockedCompany(command.companyCode());
        tenantGuard.requireCompanyAccess(actor, company.id());
        requireActive(company);
        String code = normalizeCode(command.code());
        if (departmentRepository.findByCompanyIdAndCode(company.id(), code).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "Department code already exists.");
        }
        Long parentId = resolveActiveParent(company.id(), command.parentCode());
        Department department = Department.create(
                company.id(), parentId, code, normalizeRequired(command.name()), clock.instant());
        Department saved = save(department);
        auditService.record(actor, AuditActions.DEPARTMENT_CREATE, "DEPARTMENT", saved.id(), company.id(),
                Map.of("code", saved.code(), "status", saved.status().name()));
        return DepartmentView.from(saved);
    }

    public DepartmentView move(AuthenticatedAccount actor, MoveDepartmentCommand command) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            if (command == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Department move data is required.");
            }
            Company company = findLockedCompany(command.companyCode());
            tenantGuard.requireCompanyAccess(actor, company.id());
            requireActive(company);
            Department department = findDepartment(company.id(), command.code());
            failurePlan.identify(actor, AuditActions.DEPARTMENT_MOVE, "DEPARTMENT",
                    department.id(), company.id(), Map.of("code", department.code()));
            requireVersion(department, command.version());
            Long parentId = resolveActiveParent(company.id(), command.newParentCode());
            rejectCycle(department, parentId);
            department.move(parentId, clock.instant());
            Department saved = save(department);
            auditService.record(actor, AuditActions.DEPARTMENT_MOVE, "DEPARTMENT",
                    saved.id(), company.id(), Map.of("code", saved.code()));
            return DepartmentView.from(saved);
        });
    }

    public DepartmentView changeStatus(
            AuthenticatedAccount actor, ChangeDepartmentStatusCommand command) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            if (command == null || command.status() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Department status data is required.");
            }
            Company company = findLockedCompany(command.companyCode());
            tenantGuard.requireCompanyAccess(actor, company.id());
            Department department = findDepartment(company.id(), command.code());
            DepartmentStatus previousStatus = department.status();
            failurePlan.identify(actor, AuditActions.DEPARTMENT_STATUS_CHANGE, "DEPARTMENT",
                    department.id(), company.id(), Map.of("code", department.code(),
                            "previousStatus", previousStatus.name()));
            requireVersion(department, command.version());
            if (command.status() == DepartmentStatus.ACTIVE && company.status() != CompanyStatus.ACTIVE) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
            }
            if (command.status() == DepartmentStatus.ACTIVE && department.parentDepartmentId() != null) {
                Department parent = departmentRepository.findById(department.parentDepartmentId())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.RESOURCE_NOT_FOUND, "Parent department was not found."));
                if (parent.companyId() != company.id() || parent.status() != DepartmentStatus.ACTIVE) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Parent department is inactive.");
                }
            }
            if (command.status() == DepartmentStatus.INACTIVE) {
                if (departmentRepository.existsActiveChild(department.id())) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Department has an active child.");
                }
                if (membershipRepository.existsActiveByDepartmentId(department.id())) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Department has an active membership.");
                }
            }
            department.changeStatus(command.status(), clock.instant());
            Department saved = save(department);
            auditService.record(actor, AuditActions.DEPARTMENT_STATUS_CHANGE, "DEPARTMENT",
                    saved.id(), company.id(), Map.of("code", saved.code(),
                            "status", saved.status().name(), "previousStatus", previousStatus.name()));
            return DepartmentView.from(saved);
        });
    }

    public DepartmentView update(AuthenticatedAccount actor, UpdateDepartmentCommand command) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            if (command == null || command.status() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Department data is required.");
            }
            Company company = findLockedCompany(command.companyCode());
            tenantGuard.requireCompanyAccess(actor, company.id());
            Department department = findDepartment(company.id(), command.code());
            DepartmentStatus previousStatus = department.status();
            String action = previousStatus == command.status()
                    ? AuditActions.DEPARTMENT_UPDATE : AuditActions.DEPARTMENT_STATUS_CHANGE;
            failurePlan.identify(actor, action, "DEPARTMENT", department.id(), company.id(),
                    Map.of("code", department.code(), "previousStatus", previousStatus.name()));
            requireVersion(department, command.version());
            Long parentId = resolveActiveParent(company.id(), command.parentCode());
            rejectCycle(department, parentId);
            if (command.status() == DepartmentStatus.ACTIVE && company.status() != CompanyStatus.ACTIVE) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
            }
            if (command.status() == DepartmentStatus.INACTIVE) {
                if (departmentRepository.existsActiveChild(department.id())) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Department has an active child.");
                }
                if (membershipRepository.existsActiveByDepartmentId(department.id())) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Department has an active membership.");
                }
            }
            department.update(normalizeRequired(command.name()), parentId, command.status(), clock.instant());
            Department saved = save(department);
            auditService.record(actor, action, "DEPARTMENT", saved.id(), company.id(),
                    Map.of("code", saved.code(), "status", saved.status().name(),
                            "previousStatus", previousStatus.name()));
            return DepartmentView.from(saved);
        });
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> tree(AuthenticatedAccount actor, String companyCode) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        return departmentRepository.findAllByCompanyId(company.id()).stream().map(DepartmentView::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<DepartmentView> search(AuthenticatedAccount actor, String companyCode,
                                             String search, DepartmentStatus status, int page, int size, String sort) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        var result = departmentRepository.search(company.id(), search, status, page, size, sort);
        return new PageResult<>(result.content().stream().map(DepartmentView::from).toList(),
                result.totalElements(), result.totalPages());
    }

    private Department save(Department department) {
        try {
            return departmentRepository.save(department);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "Department conflicts with existing data.");
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Department was concurrently modified.");
        }
    }

    private void rejectCycle(Department department, Long parentId) {
        Long candidateId = parentId;
        while (candidateId != null) {
            if (candidateId.equals(department.id())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Department cannot be moved below itself.");
            }
            Department candidate = departmentRepository.findById(candidateId)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND, "Parent department was not found."));
            if (candidate.companyId() != department.companyId()) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Parent department belongs to another company.");
            }
            candidateId = candidate.parentDepartmentId();
        }
    }

    private Long resolveActiveParent(long companyId, String parentCode) {
        if (parentCode == null || parentCode.isBlank()) {
            return null;
        }
        Department parent = findDepartment(companyId, parentCode);
        if (parent.status() != DepartmentStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Parent department is inactive.");
        }
        return parent.id();
    }

    private void requireActive(Company company) {
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
        }
    }

    private Company findLockedCompany(String code) {
        Company identified = findCompany(code);
        return companyRepository.findLockedById(identified.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private Company findCompany(String code) {
        return companyRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private Department findDepartment(long companyId, String code) {
        return departmentRepository.findByCompanyIdAndCode(companyId, normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Department was not found."));
    }

    private void requireVersion(Department department, long version) {
        if (department.version() != version) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Department version does not match.");
        }
    }

    private String normalizeCode(String value) {
        return BusinessCode.normalize(value);
    }

    private String normalizeRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A required value is missing.");
        }
        return value.trim();
    }
}
