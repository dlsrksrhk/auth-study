package com.sweet.authstudy.oauth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.oauth.application.OAuthUserInfoClaimSource;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HrOAuthUserInfoClaimSource implements OAuthUserInfoClaimSource {

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthClientRepository clients;
    private final OAuthSubjectRepository subjects;
    private final AccountRepository accounts;
    private final CompanyRepository companies;
    private final UserRepository users;
    private final PositionRepository positions;
    private final MembershipRepository memberships;
    private final DepartmentRepository departments;

    public HrOAuthUserInfoClaimSource(
            OAuthAuthorizationRepository authorizations,
            OAuthClientRepository clients,
            OAuthSubjectRepository subjects,
            AccountRepository accounts,
            CompanyRepository companies,
            UserRepository users,
            PositionRepository positions,
            MembershipRepository memberships,
            DepartmentRepository departments) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.subjects = subjects;
        this.accounts = accounts;
        this.companies = companies;
        this.users = users;
        this.positions = positions;
        this.memberships = memberships;
        this.departments = departments;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Snapshot> load(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) return Optional.empty();
        var accessToken = authorizations.findByAccessTokenHash(sha256(rawAccessToken)).orElse(null);
        if (accessToken == null) return Optional.empty();
        var authorization = authorizations.findById(accessToken.authorizationId()).orElse(null);
        if (authorization == null) return Optional.empty();
        var client = clients.findById(authorization.registeredClientId()).orElse(null);
        var subject = subjects.findByAccountId(authorization.principalAccountId()).orElse(null);
        var account = accounts.findById(authorization.principalAccountId()).orElse(null);
        var company = companies.findById(authorization.companyId()).orElse(null);
        if (client == null || subject == null || account == null || company == null
                || account.userId() == null) {
            return Optional.empty();
        }
        var user = users.findById(account.userId()).orElse(null);
        if (user == null) return Optional.empty();

        Optional<CodeName> position = positions.findAllByCompanyId(company.id()).stream()
                .filter(candidate -> candidate.id() != null && candidate.id() == user.positionId())
                .filter(candidate -> candidate.companyId() == company.id() && candidate.active())
                .findFirst()
                .map(candidate -> new CodeName(candidate.code(), candidate.name()));
        Map<Long, Department> currentDepartments = departments.findAllByCompanyId(company.id()).stream()
                .filter(candidate -> candidate.id() != null)
                .collect(Collectors.toUnmodifiableMap(Department::id, Function.identity()));
        List<Membership> currentMemberships = memberships.findAllByUserId(user.id()).stream()
                .map(membership -> {
                    Department department = currentDepartments.get(membership.departmentId());
                    if (department == null) return null;
                    return new Membership(
                            membership.companyId(), membership.userId(),
                            new CodeName(department.code(), department.name()),
                            status(department.status() == DepartmentStatus.ACTIVE),
                            membership.primary(), Optional.ofNullable(membership.endedAt()));
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return Optional.of(new Snapshot(
                new Authorization(
                        authorization.id(), authorization.registeredClientId(),
                        authorization.principalAccountId(), authorization.companyId(),
                        authorization.subject().toString(), authorization.authorizedScopes(),
                        status(authorization.status()
                                == com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.ACTIVE),
                        authorization.expiresAt(), Optional.ofNullable(authorization.revokedAt())),
                new AccessToken(
                        accessToken.authorizationId(), accessToken.audience(), accessToken.expiresAt(),
                        Optional.ofNullable(accessToken.revokedAt())),
                new Client(client.id(), client.companyId(), client.clientId(),
                        status(client.status() == OAuthClientStatus.ACTIVE)),
                new Subject(subject.accountId(), subject.subject().toString()),
                new Account(
                        account.id(), Optional.ofNullable(account.companyId()), Optional.ofNullable(account.userId()),
                        Optional.ofNullable(account.loginEmail()), status(account.status() == AccountStatus.ACTIVE),
                        account.mustChangePassword(), Optional.ofNullable(account.lockedUntil()),
                        account.roles().stream().map(Enum::name).toList()),
                new Company(company.id(), company.code(), company.name(),
                        status(company.status() == CompanyStatus.ACTIVE)),
                new User(user.id(), user.companyId(), user.code(), user.name(),
                        status(user.status() == UserStatus.ACTIVE)),
                position, currentMemberships));
    }

    private static Status status(boolean active) {
        return active ? Status.ACTIVE : Status.INACTIVE;
    }

    private static String sha256(String rawValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawValue.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
