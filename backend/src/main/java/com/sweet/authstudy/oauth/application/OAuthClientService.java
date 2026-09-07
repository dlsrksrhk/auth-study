package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.identity.application.OAuthGrantRevocationPort;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.oauth.domain.*;
import com.sweet.authstudy.shared.application.PageResult;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.sweet.authstudy.oauth.application.OAuthClientCommands.*;

@Service
public class OAuthClientService {

    private static final int CLIENT_ID_ATTEMPTS = 10;

    private final CompanyRepository companyRepository;
    private final OAuthClientRepository clientRepository;
    private final TenantGuard tenantGuard;
    private final PasswordEncoder passwordEncoder;
    private final OAuthClientSecretGenerator secretGenerator;
    private final OAuthGrantRevocationPort oauthGrants;
    private final Clock clock;
    private final AuditService audit;

    public OAuthClientService(
            CompanyRepository companyRepository,
            OAuthClientRepository clientRepository,
            TenantGuard tenantGuard,
            PasswordEncoder passwordEncoder,
            OAuthClientSecretGenerator secretGenerator,
            OAuthGrantRevocationPort oauthGrants,
            Clock clock, AuditService audit) {
        this.companyRepository = companyRepository;
        this.clientRepository = clientRepository;
        this.tenantGuard = tenantGuard;
        this.passwordEncoder = passwordEncoder;
        this.secretGenerator = secretGenerator;
        this.oauthGrants = oauthGrants;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public ClientSecretResult create(AuthenticatedAccount actor, CreateClient command) {
        if (command == null) {
            throw validation("OAuth client details are required.");
        }
        Company company = requireActiveCompany(actor, command.companyCode());
        OAuthClientTrust trust = requireCreateTrust(actor, command.trust());
        Instant now = clock.instant();
        String clientId = generateUniqueClientId();
        String rawSecret = command.publicClient() ? null : secretGenerator.generateClientSecret();
        Set<OAuthClientSecret> secrets = rawSecret == null
                ? Set.of()
                : Set.of(toSecret(rawSecret, now));
        OAuthClient candidate;
        try {
            candidate = OAuthClient.create(
                    company.id(), clientId, required(command.displayName()), command.publicClient(),
                    command.redirectUris(), command.postLogoutRedirectUris(), command.scopes(),
                    trust, secrets, now);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation(exception.getMessage());
        }
        OAuthClient saved = clientRepository.save(candidate);
        audit(actor, AuditActions.OAUTH_CLIENT_CREATED, saved);
        return new ClientSecretResult(OAuthClientView.from(saved, company.code()), rawSecret);
    }

    @Transactional
    public OAuthClientView update(
            AuthenticatedAccount actor, String clientId, UpdateClient command) {
        if (command == null) {
            throw validation("OAuth client details are required.");
        }
        ClientContext context = requireClientAccess(actor, clientId);
        OAuthClient client = context.client();
        if (client.version() != command.version()) {
            throw new ApiException(
                    ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "OAuth client version does not match.");
        }
        OAuthClientTrust trust = requireUpdateTrust(actor, client.trust(), command.trust());
        OAuthClientStatus status = command.status() == null ? client.status() : requireStatus(command.status());
        Instant now = clock.instant();
        if (client.status() != OAuthClientStatus.DISABLED && status == OAuthClientStatus.DISABLED) {
            oauthGrants.revokeClient(client.id(), now);
        }
        try {
            client.update(
                    required(command.displayName()), status, trust, command.redirectUris(),
                    command.postLogoutRedirectUris(), command.scopes(), now);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation(exception.getMessage());
        }
        OAuthClient saved = clientRepository.save(client);
        audit(actor, AuditActions.OAUTH_CLIENT_UPDATED, saved);
        return OAuthClientView.from(saved, context.company().code());
    }

    @Transactional
    public ClientSecretResult rotateSecret(AuthenticatedAccount actor, String clientId) {
        ClientContext context = requireClientAccess(actor, clientId);
        OAuthClient client = context.client();
        if (client.publicClient()) {
            throw invalidState("A public OAuth client has no secret.");
        }
        if (client.status() != OAuthClientStatus.ACTIVE) {
            throw invalidState("A disabled OAuth client secret cannot be rotated.");
        }
        Instant now = clock.instant();
        oauthGrants.revokeClient(client.id(), now);
        String rawSecret = secretGenerator.generateClientSecret();
        client.revokeActiveSecrets(now);
        client = clientRepository.save(client);
        client.addSecret(toSecret(rawSecret, now), now);
        OAuthClient saved = clientRepository.save(client);
        audit(actor, AuditActions.OAUTH_CLIENT_SECRET_ROTATED, saved);
        return new ClientSecretResult(
                OAuthClientView.from(saved, context.company().code()), rawSecret);
    }

    @Transactional
    public OAuthClientView revokeSecret(AuthenticatedAccount actor, String clientId) {
        ClientContext context = requireClientAccess(actor, clientId);
        OAuthClient client = context.client();
        if (client.publicClient()) {
            throw invalidState("A public OAuth client has no secret.");
        }
        Instant now = clock.instant();
        oauthGrants.revokeClient(client.id(), now);
        client.revokeActiveSecrets(now);
        OAuthClient saved = clientRepository.save(client);
        audit(actor, AuditActions.OAUTH_CLIENT_SECRET_REVOKED, saved);
        return OAuthClientView.from(saved, context.company().code());
    }

    @Transactional(readOnly = true)
    public OAuthClientView find(AuthenticatedAccount actor, String clientId) {
        ClientContext context = requireClientAccess(actor, clientId);
        return OAuthClientView.from(context.client(), context.company().code());
    }

    @Transactional(readOnly = true)
    public List<OAuthClientView> list(AuthenticatedAccount actor, String companyCode) {
        Company company = requireActiveCompany(actor, companyCode);
        return clientRepository.findByCompanyId(company.id()).stream()
                .map(client -> OAuthClientView.from(client, company.code()))
                .toList();
    }

    @Transactional(readOnly = true)
    public void requireClientInCompany(AuthenticatedAccount actor, String companyCode, String clientId) {
        Company company = requireActiveCompany(actor, companyCode);
        ClientContext context = requireClientAccess(actor, clientId);
        if (context.client().companyId() != company.id()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "OAuth client was not found.");
        }
    }

    @Transactional(readOnly = true)
    public PageResult<OAuthClientView> list(
            AuthenticatedAccount actor, String companyCode, int page, int size) {
        Company company = requireActiveCompany(actor, companyCode);
        var result = clientRepository.findPageByCompanyId(company.id(), page, size);
        return mapPage(result, company.code());
    }

    @Transactional(readOnly = true)
    public PageResult<OAuthClientView> listAll(
            AuthenticatedAccount actor, String companyCode, int page, int size) {
        tenantGuard.requireSystemAdmin(actor);
        if (companyCode != null && !companyCode.isBlank()) {
            Company company = companyRepository.findByCode(BusinessCode.normalize(companyCode))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
            return mapPage(clientRepository.findPageByCompanyId(company.id(), page, size), company.code());
        }
        var result = clientRepository.findPage(page, size);
        return new PageResult<>(result.content().stream().map(client -> {
            Company company = companyRepository.findById(client.companyId()).orElseThrow(() ->
                    new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
            return OAuthClientView.from(client, company.code());
        }).toList(), result.totalElements(), result.totalPages());
    }

    @Transactional
    public OAuthClientView disable(AuthenticatedAccount actor, String clientId, long version) {
        return changeStatus(actor, clientId, OAuthClientStatus.DISABLED, version);
    }

    @Transactional
    public OAuthClientView enable(AuthenticatedAccount actor, String clientId, long version) {
        return changeStatus(actor, clientId, OAuthClientStatus.ACTIVE, version);
    }

    private OAuthClientView changeStatus(AuthenticatedAccount actor, String clientId,
                                         OAuthClientStatus status, long version) {
        ClientContext context = requireClientAccess(actor, clientId);
        OAuthClient client = context.client();
        if (client.version() != version) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "OAuth client version does not match.");
        }
        if (status == OAuthClientStatus.DISABLED) oauthGrants.revokeClient(client.id(), clock.instant());
        client.update(client.displayName(), status, client.trust(), client.redirectUris(),
                client.postLogoutRedirectUris(), client.scopes(), clock.instant());
        OAuthClient saved = clientRepository.save(client);
        audit(actor, status == OAuthClientStatus.DISABLED ? AuditActions.OAUTH_CLIENT_DISABLED : AuditActions.OAUTH_CLIENT_ENABLED, saved);
        return OAuthClientView.from(saved, context.company().code());
    }

    private void audit(AuthenticatedAccount actor, String action, OAuthClient client) {
        audit.record(actor, action, "OAUTH_CLIENT", client.id(), client.companyId(), java.util.Map.of());
    }

    private PageResult<OAuthClientView> mapPage(PageResult<OAuthClient> result, String companyCode) {
        return new PageResult<>(result.content().stream()
                .map(client -> OAuthClientView.from(client, companyCode)).toList(),
                result.totalElements(), result.totalPages());
    }

    private Company requireActiveCompany(AuthenticatedAccount actor, String companyCode) {
        String normalizedCode;
        try {
            normalizedCode = BusinessCode.normalize(companyCode);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation(exception.getMessage());
        }
        Company company = companyRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        tenantGuard.requireCompanyAccess(actor, company.id());
        if (company.status() != CompanyStatus.ACTIVE) {
            throw invalidState("OAuth clients cannot be managed for an inactive company.");
        }
        return company;
    }

    private ClientContext requireClientAccess(AuthenticatedAccount actor, String clientId) {
        String externalId = required(clientId);
        OAuthClient client = clientRepository.findByClientId(externalId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "OAuth client was not found."));
        Company company = companyRepository.findById(client.companyId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        tenantGuard.requireCompanyAccess(actor, company.id());
        if (company.status() != CompanyStatus.ACTIVE) {
            throw invalidState("OAuth clients cannot be managed for an inactive company.");
        }
        return new ClientContext(client, company);
    }

    private OAuthClientTrust requireCreateTrust(
            AuthenticatedAccount actor, OAuthClientTrust trust) {
        OAuthClientTrust requested = requireTrust(trust);
        if (requested == OAuthClientTrust.TRUSTED_FIRST_PARTY
                && !actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Only a system administrator may create a trusted client.");
        }
        return requested;
    }

    private OAuthClientTrust requireUpdateTrust(
            AuthenticatedAccount actor, OAuthClientTrust existing, OAuthClientTrust requested) {
        OAuthClientTrust required = requireTrust(requested);
        if (!actor.roles().contains(AccountRole.SYSTEM_ADMIN)
                && (required == OAuthClientTrust.TRUSTED_FIRST_PARTY || existing != required)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Only a system administrator may change client trust.");
        }
        return required;
    }

    private OAuthClientTrust requireTrust(OAuthClientTrust trust) {
        if (trust == null) {
            throw validation("OAuth client trust is required.");
        }
        return trust;
    }

    private OAuthClientStatus requireStatus(OAuthClientStatus status) {
        if (status == null) {
            throw validation("OAuth client status is required.");
        }
        return status;
    }

    private String generateUniqueClientId() {
        for (int attempt = 0; attempt < CLIENT_ID_ATTEMPTS; attempt++) {
            String candidate = secretGenerator.generateClientId();
            if (clientRepository.findByClientId(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw invalidState("A unique OAuth client identifier could not be generated.");
    }

    private OAuthClientSecret toSecret(String rawSecret, Instant now) {
        if (rawSecret == null || rawSecret.length() < 4) {
            throw new IllegalStateException("Generated OAuth client secret is invalid.");
        }
        return OAuthClientSecret.create(
                passwordEncoder.encode(rawSecret), rawSecret.substring(rawSecret.length() - 4),
                now, null);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw validation("A required OAuth client value is missing.");
        }
        return value.trim();
    }

    private ApiException validation(String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                message == null || message.isBlank() ? "OAuth client details are invalid." : message);
    }

    private ApiException invalidState(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    private record ClientContext(OAuthClient client, Company company) {
    }
}
