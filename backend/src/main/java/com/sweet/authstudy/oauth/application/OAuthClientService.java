package com.sweet.authstudy.oauth.application;

import static com.sweet.authstudy.oauth.application.OAuthClientCommands.ClientSecretResult;
import static com.sweet.authstudy.oauth.application.OAuthClientCommands.CreateClient;
import static com.sweet.authstudy.oauth.application.OAuthClientCommands.UpdateClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.application.OAuthGrantRevocationPort;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public OAuthClientService(
            CompanyRepository companyRepository,
            OAuthClientRepository clientRepository,
            TenantGuard tenantGuard,
            PasswordEncoder passwordEncoder,
            OAuthClientSecretGenerator secretGenerator,
            OAuthGrantRevocationPort oauthGrants,
            Clock clock) {
        this.companyRepository = companyRepository;
        this.clientRepository = clientRepository;
        this.tenantGuard = tenantGuard;
        this.passwordEncoder = passwordEncoder;
        this.secretGenerator = secretGenerator;
        this.oauthGrants = oauthGrants;
        this.clock = clock;
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
        OAuthClientStatus status = requireStatus(command.status());
        Instant now = clock.instant();
        if (status == OAuthClientStatus.DISABLED) {
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
        return OAuthClientView.from(clientRepository.save(client), context.company().code());
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
        if (existing != required && !actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
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
