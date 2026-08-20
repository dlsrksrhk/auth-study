package com.sweet.authstudy.hr.membership.application;

import static com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import static com.sweet.authstudy.hr.membership.application.MembershipCommands.UpdateMembershipCommand;

import java.time.Clock;
import java.util.List;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.authorization.AdministrativeTargetGuard;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.hr.membership.domain.DepartmentMembership;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

    private final MembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final Clock clock;
    private final TenantGuard tenantGuard;
    private final AdministrativeTargetGuard targetGuard;

    public MembershipService(
            MembershipRepository membershipRepository,
            CompanyRepository companyRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository, TenantGuard tenantGuard,
            AdministrativeTargetGuard targetGuard,
            Clock clock) {
        this.membershipRepository = membershipRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.tenantGuard = tenantGuard;
        this.targetGuard = targetGuard;
        this.clock = clock;
    }

    @Transactional
    public MembershipView assign(AuthenticatedAccount actor, AssignMembershipCommand command) {
        if (command == null || command.role() == null || command.startedAt() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Membership data is required.");
        }
        Company company = findLockedCompany(command.companyCode());
        tenantGuard.requireCompanyAccess(actor, company.id());
        requireActive(company);
        HrUser user = findUser(company.id(), command.userCode());
        targetGuard.requireMayMutateUser(actor, user.id());
        Department department = findDepartment(company.id(), command.departmentCode());
        requireAssignable(user, department);
        rejectAssignConflicts(user.id(), department.id(), command.role(), command.primary());
        DepartmentMembership membership = DepartmentMembership.create(
                company.id(), user.id(), department.id(), command.role(), command.primary(),
                command.startedAt(), clock.instant());
        return MembershipView.from(save(membership));
    }

    @Transactional
    public MembershipView update(AuthenticatedAccount actor, UpdateMembershipCommand command) {
        if (command == null || command.role() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Membership update data is required.");
        }
        Company company = findLockedCompany(command.companyCode());
        tenantGuard.requireCompanyAccess(actor, company.id());
        requireActive(company);
        HrUser user = findUser(company.id(), command.userCode());
        targetGuard.requireMayMutateUser(actor, user.id());
        DepartmentMembership membership = findMembership(command.membershipId());
        requireOwnership(membership, company, user);
        requireVersion(membership, command.version());
        requireActive(membership);
        Department department = findDepartment(membership.departmentId());
        requireAssignable(user, department);
        rejectUpdateConflicts(user, membership, command.role(), command.primary());
        membership.update(command.role(), command.primary(), clock.instant());
        return MembershipView.from(save(membership));
    }

    @Transactional
    public MembershipView end(
            AuthenticatedAccount actor, String companyCode, String userCode, long membershipId, long version) {
        Company company = findLockedCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        HrUser user = findUser(company.id(), userCode);
        targetGuard.requireMayMutateUser(actor, user.id());
        DepartmentMembership membership = findMembership(membershipId);
        requireOwnership(membership, company, user);
        requireVersion(membership, version);
        requireActive(membership);
        Department department = findDepartment(membership.departmentId());
        requireDepartmentOwnership(company, department);
        rejectRemovingPrimaryFromActiveUser(user, membership);
        var now = clock.instant();
        membership.end(now, now);
        return MembershipView.from(save(membership));
    }

    @Transactional(readOnly = true)
    public List<MembershipView> listByUser(
            AuthenticatedAccount actor, String companyCode, String userCode) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        HrUser user = findUser(company.id(), userCode);
        return membershipRepository.findAllByUserId(user.id()).stream().map(MembershipView::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<MembershipView> searchByUser(
            AuthenticatedAccount actor, String companyCode, String userCode,
            String search, Boolean active, int page, int size, String sort) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        HrUser user = findUser(company.id(), userCode);
        var result = membershipRepository.searchByUser(
                company.id(), user.id(), search, active, page, size, sort);
        return new PageResult<>(result.content().stream().map(MembershipView::from).toList(),
                result.totalElements(), result.totalPages());
    }

    private void rejectAssignConflicts(
            long userId, long departmentId, DepartmentRole role, boolean primary) {
        if (membershipRepository.existsActiveByUserIdAndDepartmentId(userId, departmentId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Active membership already exists.");
        }
        if (primary && membershipRepository.existsActivePrimaryByUserId(userId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Active primary membership already exists.");
        }
        if (role == DepartmentRole.HEAD
                && membershipRepository.existsActiveHeadByDepartmentId(departmentId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Department already has an active head.");
        }
    }

    private void rejectUpdateConflicts(
            HrUser user, DepartmentMembership membership, DepartmentRole role, boolean primary) {
        if (!primary) {
            rejectRemovingPrimaryFromActiveUser(user, membership);
        }
        if (primary && membershipRepository.existsActivePrimaryByUserIdExcluding(
                membership.userId(), membership.id())) {
            if (user.status() != UserStatus.ACTIVE) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Active primary membership already exists.");
            }
            DepartmentMembership previousPrimary = membershipRepository.findActivePrimaryByUserId(user.id())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_STATE, "Active primary membership was not found."));
            previousPrimary.update(previousPrimary.role(), false, clock.instant());
            save(previousPrimary);
        }
        if (role == DepartmentRole.HEAD
                && membershipRepository.existsActiveHeadByDepartmentIdExcluding(
                        membership.departmentId(), membership.id())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Department already has an active head.");
        }
    }

    private void rejectRemovingPrimaryFromActiveUser(
            HrUser user, DepartmentMembership membership) {
        if (user.status() == UserStatus.ACTIVE && membership.primary()) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE, "Active user must retain one primary membership.");
        }
    }

    private DepartmentMembership save(DepartmentMembership membership) {
        try {
            return membershipRepository.save(membership);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Active membership conflicts with existing data.");
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Membership was concurrently modified.");
        }
    }

    private void requireAssignable(HrUser user, Department department) {
        if (user.companyId() != department.companyId()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "User and department belong to different companies.");
        }
        if (user.status() == UserStatus.RESIGNED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Resigned user cannot have an active membership.");
        }
        if (department.status() != DepartmentStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Department is inactive.");
        }
    }

    private void requireOwnership(DepartmentMembership membership, Company company, HrUser user) {
        if (membership.companyId() != company.id() || membership.userId() != user.id()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Membership was not found.");
        }
    }

    private void requireDepartmentOwnership(Company company, Department department) {
        if (department.companyId() != company.id()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Department was not found.");
        }
    }

    private void requireVersion(DepartmentMembership membership, long version) {
        if (membership.version() != version) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Membership version does not match.");
        }
    }

    private void requireActive(DepartmentMembership membership) {
        if (membership.endedAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Membership has already ended.");
        }
    }

    private DepartmentMembership findMembership(long id) {
        return membershipRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Membership was not found."));
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

    private HrUser findUser(long companyId, String code) {
        return userRepository.findByCompanyIdAndCode(companyId, normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User was not found."));
    }

    private Department findDepartment(long companyId, String code) {
        return departmentRepository.findByCompanyIdAndCode(companyId, normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Department was not found."));
    }

    private Department findDepartment(long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Department was not found."));
    }

    private String normalizeCode(String value) {
        return BusinessCode.normalize(value);
    }
}
