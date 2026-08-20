package com.sweet.authstudy.hr.position.application;

import static com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import static com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionService {

    private final PositionRepository positionRepository;
    private final CompanyRepository companyRepository;
    private final Clock clock;
    private final TenantGuard tenantGuard;

    public PositionService(
            PositionRepository positionRepository, CompanyRepository companyRepository,
            TenantGuard tenantGuard, Clock clock) {
        this.positionRepository = positionRepository;
        this.companyRepository = companyRepository;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
    }

    @Transactional
    public PositionView create(AuthenticatedAccount actor, String companyCode, CreatePositionCommand command) {
        Company company = findCompany(normalizeCode(companyCode));
        tenantGuard.requireCompanyAccess(actor, company.id());
        String code = normalizeCode(command.code());
        if (positionRepository.findByCompanyIdAndCode(company.id(), code).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "Position code already exists.");
        }
        Position position = Position.create(
                company.id(),
                code,
                normalizeRequiredValue(command.name()),
                command.level(),
                command.displayOrder(),
                true,
                clock.instant());
        return PositionView.from(positionRepository.save(position));
    }

    @Transactional
    public PositionView update(
            AuthenticatedAccount actor, String companyCode, String code, UpdatePositionCommand command) {
        Company company = findCompany(normalizeCode(companyCode));
        tenantGuard.requireCompanyAccess(actor, company.id());
        Position position = findPosition(company.id(), normalizeCode(code));
        if (position.version() != command.version()) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Position version does not match.");
        }
        position.update(
                normalizeRequiredValue(command.name()),
                command.level(),
                command.displayOrder(),
                command.active(),
                clock.instant());
        return PositionView.from(positionRepository.save(position));
    }

    @Transactional(readOnly = true)
    public PositionView find(AuthenticatedAccount actor, String companyCode, String code) {
        Company company = findCompany(normalizeCode(companyCode));
        tenantGuard.requireCompanyAccess(actor, company.id());
        return PositionView.from(findPosition(company.id(), normalizeCode(code)));
    }

    @Transactional(readOnly = true)
    public List<PositionView> list(AuthenticatedAccount actor, String companyCode) {
        Company company = findCompany(normalizeCode(companyCode));
        tenantGuard.requireCompanyAccess(actor, company.id());
        return positionRepository.findAllByCompanyId(company.id()).stream().map(PositionView::from).toList();
    }

    @Transactional
    public void createDefaults(AuthenticatedAccount actor, long companyId) {
        tenantGuard.requireCompanyAccess(actor, companyId);
        createDefault(companyId, "EMPLOYEE", "사원", 10);
        createDefault(companyId, "ASSISTANT_MANAGER", "대리", 20);
        createDefault(companyId, "MANAGER", "과장", 30);
        createDefault(companyId, "DEPUTY_GENERAL_MANAGER", "차장", 40);
        createDefault(companyId, "GENERAL_MANAGER", "부장", 50);
    }

    private void createDefault(long companyId, String code, String name, int levelAndOrder) {
        Position position = Position.create(
                companyId,
                code,
                name,
                levelAndOrder,
                levelAndOrder,
                true,
                clock.instant());
        positionRepository.save(position);
    }

    private Company findCompany(String code) {
        return companyRepository.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private Position findPosition(long companyId, String code) {
        return positionRepository.findByCompanyIdAndCode(companyId, code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Position was not found."));
    }

    private String normalizeCode(String value) {
        return normalizeRequiredValue(value).toUpperCase(Locale.ROOT);
    }

    private String normalizeRequiredValue(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A required value is missing.");
        }
        return value.trim();
    }
}
