package com.sweet.authstudy.oauth.application;

import static com.sweet.authstudy.oauth.application.OAuthClientCommands.CreateClient;
import static com.sweet.authstudy.oauth.application.OAuthClientCommands.UpdateClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.oauth.infrastructure.SecureOAuthClientSecretGenerator;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OAuthClientServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final URI CALLBACK = URI.create("https://rp.example/callback");
    private static final URI LOGOUT = URI.create("https://rp.example/logout");

    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final InMemoryOAuthClientRepository clients = new InMemoryOAuthClientRepository();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final QueueGenerator generator = new QueueGenerator();
    private final OAuthClientService service = new OAuthClientService(
            companies, clients, new TenantGuard(companies), passwordEncoder, generator,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Company acme;
    private Company other;

    @BeforeEach
    void setUp() {
        acme = company(41L, "ACME", CompanyStatus.ACTIVE);
        other = company(73L, "OTHER", CompanyStatus.ACTIVE);
        register(acme);
        register(other);
    }

    @Test
    void company_admin_creates_a_confidential_consent_client_with_only_a_bcrypt_hash_persisted() {
        generator.clientIds.add("client-one");
        generator.secrets.add("raw-secret-value-1234");

        OAuthClientCommands.ClientSecretResult result = service.create(companyAdmin(41L),
                createCommand("ACME", false, OAuthClientTrust.CONSENT_REQUIRED));

        assertThat(result.oneTimeSecret()).isEqualTo("raw-secret-value-1234");
        assertThat(result.toString()).doesNotContain(result.oneTimeSecret());
        assertThat(result.client().clientId()).isEqualTo("client-one");
        OAuthClient stored = clients.findByClientId("client-one").orElseThrow();
        assertThat(stored.companyId()).isEqualTo(41L);
        assertThat(stored.secrets()).singleElement().satisfies(secret -> {
            assertThat(secret.secretHash()).isNotEqualTo(result.oneTimeSecret());
            assertThat(passwordEncoder.matches(result.oneTimeSecret(), secret.secretHash())).isTrue();
            assertThat(secret.secretHint()).isEqualTo("1234");
            assertThat(secret.createdAt()).isEqualTo(NOW);
            assertThat(secret.expiresAt()).isNull();
            assertThat(secret.revokedAt()).isNull();
        });
        assertThat(service.find(companyAdmin(41L), "client-one").toString())
                .doesNotContain(result.oneTimeSecret(), stored.secrets().iterator().next().secretHash());
    }

    @Test
    void public_client_has_no_secret_and_cannot_rotate_one() {
        generator.clientIds.add("public-client");

        OAuthClientCommands.ClientSecretResult created = service.create(companyAdmin(41L),
                createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED));

        assertThat(created.oneTimeSecret()).isNull();
        assertThat(clients.findByClientId("public-client").orElseThrow().secrets()).isEmpty();
        assertError(ErrorCode.INVALID_STATE,
                () -> service.rotateSecret(companyAdmin(41L), "public-client"));
    }

    @Test
    void company_admin_cannot_cross_tenants_or_escalate_trust() {
        generator.clientIds.add("never-created");

        assertError(ErrorCode.FORBIDDEN, () -> service.create(companyAdmin(41L),
                createCommand("OTHER", false, OAuthClientTrust.CONSENT_REQUIRED)));
        assertError(ErrorCode.FORBIDDEN, () -> service.create(companyAdmin(41L),
                createCommand("ACME", false, OAuthClientTrust.TRUSTED_FIRST_PARTY)));
        assertThat(clients.findByCompanyId(41L)).isEmpty();
        assertThat(clients.findByCompanyId(73L)).isEmpty();
    }

    @Test
    void system_admin_can_target_any_company_and_create_a_trusted_client() {
        generator.clientIds.add("trusted-client");
        generator.secrets.add("trusted-raw-secret-9999");

        OAuthClientCommands.ClientSecretResult result = service.create(systemAdmin(),
                createCommand("OTHER", false, OAuthClientTrust.TRUSTED_FIRST_PARTY));

        assertThat(result.client().companyCode()).isEqualTo("OTHER");
        assertThat(result.client().trust()).isEqualTo(OAuthClientTrust.TRUSTED_FIRST_PARTY);
        assertThat(clients.findByClientId("trusted-client").orElseThrow().companyId()).isEqualTo(73L);
    }

    @Test
    void creation_preserves_exact_redirects_and_the_supported_scope_set() {
        generator.clientIds.add("exact-client");
        CreateClient command = new CreateClient(
                "ACME", "Exact RP", true,
                Set.of(CALLBACK, URI.create("http://rp.localhost/callback")),
                Set.of(LOGOUT), Set.of("openid", "profile", "hr.company"),
                OAuthClientTrust.CONSENT_REQUIRED);

        service.create(companyAdmin(41L), command);

        OAuthClient stored = clients.findByClientId("exact-client").orElseThrow();
        assertThat(stored.redirectUris()).containsExactlyInAnyOrderElementsOf(command.redirectUris());
        assertThat(stored.postLogoutRedirectUris()).containsExactly(LOGOUT);
        assertThat(stored.scopes()).containsExactlyInAnyOrder("openid", "profile", "hr.company");
        assertThat(stored.allowsRedirect(CALLBACK)).isTrue();
        assertThat(stored.allowsRedirect(URI.create("https://RP.EXAMPLE/callback"))).isFalse();
        assertThat(stored.allowsRedirect(URI.create("https://rp.example/callback?next=1"))).isFalse();
    }

    @Test
    void creation_maps_unsafe_redirects_and_unsupported_scopes_to_validation_errors() {
        generator.clientIds.addAll(List.of("invalid-redirect", "invalid-scope"));
        CreateClient invalidRedirect = new CreateClient(
                "ACME", "Invalid", true, Set.of(URI.create("http://rp.example/callback")),
                Set.of(), Set.of("openid"), OAuthClientTrust.CONSENT_REQUIRED);
        CreateClient invalidScope = new CreateClient(
                "ACME", "Invalid", true, Set.of(CALLBACK), Set.of(),
                Set.of("openid", "payments.write"), OAuthClientTrust.CONSENT_REQUIRED);

        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.create(companyAdmin(41L), invalidRedirect));
        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.create(companyAdmin(41L), invalidScope));
        assertThat(clients.findByCompanyId(41L)).isEmpty();
    }

    @Test
    void missing_and_disabled_companies_return_stable_application_errors() {
        when(companies.findByCode("MISSING")).thenReturn(Optional.empty());
        Company disabled = company(88L, "DISABLED", CompanyStatus.INACTIVE);
        register(disabled);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.create(systemAdmin(),
                createCommand("MISSING", true, OAuthClientTrust.CONSENT_REQUIRED)));
        assertError(ErrorCode.INVALID_STATE, () -> service.create(systemAdmin(),
                createCommand("DISABLED", true, OAuthClientTrust.CONSENT_REQUIRED)));
    }

    @Test
    void update_changes_management_fields_but_not_client_identity_or_client_type() {
        generator.clientIds.add("update-client");
        OAuthClientView created = service.create(systemAdmin(),
                createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED)).client();
        UpdateClient command = new UpdateClient(
                "Updated RP", Set.of(URI.create("https://new.example/callback")), Set.of(LOGOUT),
                Set.of("openid", "email"), OAuthClientTrust.TRUSTED_FIRST_PARTY,
                OAuthClientStatus.DISABLED, created.version());

        OAuthClientView updated = service.update(systemAdmin(), "update-client", command);

        assertThat(updated.clientId()).isEqualTo("update-client");
        assertThat(updated.publicClient()).isTrue();
        assertThat(updated.displayName()).isEqualTo("Updated RP");
        assertThat(updated.status()).isEqualTo(OAuthClientStatus.DISABLED);
        assertThat(updated.trust()).isEqualTo(OAuthClientTrust.TRUSTED_FIRST_PARTY);
        assertThat(updated.redirectUris()).containsExactly(URI.create("https://new.example/callback"));
        assertThat(updated.scopes()).containsExactlyInAnyOrder("openid", "email");
    }

    @Test
    void company_admin_cannot_update_another_tenant_or_promote_trust() {
        generator.clientIds.add("other-client");
        OAuthClientView created = service.create(systemAdmin(),
                createCommand("OTHER", true, OAuthClientTrust.CONSENT_REQUIRED)).client();
        UpdateClient trusted = new UpdateClient(
                "Other", Set.of(CALLBACK), Set.of(LOGOUT), Set.of("openid"),
                OAuthClientTrust.TRUSTED_FIRST_PARTY, OAuthClientStatus.ACTIVE, created.version());

        assertError(ErrorCode.FORBIDDEN,
                () -> service.update(companyAdmin(41L), "other-client", trusted));

        generator.clientIds.add("own-client");
        OAuthClientView own = service.create(companyAdmin(41L),
                createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED)).client();
        UpdateClient ownTrusted = new UpdateClient(
                "Own", Set.of(CALLBACK), Set.of(LOGOUT), Set.of("openid"),
                OAuthClientTrust.TRUSTED_FIRST_PARTY, OAuthClientStatus.ACTIVE, own.version());
        assertError(ErrorCode.FORBIDDEN,
                () -> service.update(companyAdmin(41L), "own-client", ownTrusted));
    }

    @Test
    void stale_update_version_is_rejected() {
        generator.clientIds.add("versioned-client");
        service.create(systemAdmin(), createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED));
        UpdateClient stale = new UpdateClient(
                "Stale", Set.of(CALLBACK), Set.of(), Set.of("openid"),
                OAuthClientTrust.CONSENT_REQUIRED, OAuthClientStatus.ACTIVE, 99L);

        assertError(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                () -> service.update(systemAdmin(), "versioned-client", stale));
    }

    @Test
    void rotation_revokes_the_previous_secret_and_returns_only_the_new_raw_value_once() {
        generator.clientIds.add("rotate-client");
        generator.secrets.add("first-raw-secret-1111");
        generator.secrets.add("second-raw-secret-2222");
        service.create(companyAdmin(41L),
                createCommand("ACME", false, OAuthClientTrust.CONSENT_REQUIRED));

        OAuthClientCommands.ClientSecretResult rotated =
                service.rotateSecret(companyAdmin(41L), "rotate-client");

        assertThat(rotated.oneTimeSecret()).isEqualTo("second-raw-secret-2222");
        OAuthClient stored = clients.findByClientId("rotate-client").orElseThrow();
        assertThat(stored.secrets()).hasSize(2);
        assertThat(stored.secrets()).filteredOn(secret -> secret.revokedAt() == null)
                .singleElement().satisfies(secret -> {
                    assertThat(passwordEncoder.matches(rotated.oneTimeSecret(), secret.secretHash())).isTrue();
                    assertThat(secret.secretHint()).isEqualTo("2222");
                    assertThat(secret.secretHash()).isNotEqualTo(rotated.oneTimeSecret());
                });
        assertThat(stored.secrets()).filteredOn(secret -> NOW.equals(secret.revokedAt()))
                .singleElement().satisfies(secret ->
                        assertThat(passwordEncoder.matches("first-raw-secret-1111", secret.secretHash())).isTrue());
        assertThat(service.find(companyAdmin(41L), "rotate-client").toString())
                .doesNotContain(rotated.oneTimeSecret());
    }

    @Test
    void rotation_rejects_disabled_and_missing_clients() {
        generator.clientIds.add("disabled-client");
        generator.secrets.add("disabled-secret-1111");
        OAuthClientView created = service.create(systemAdmin(),
                createCommand("ACME", false, OAuthClientTrust.CONSENT_REQUIRED)).client();
        service.update(systemAdmin(), "disabled-client", new UpdateClient(
                "Disabled", Set.of(CALLBACK), Set.of(), Set.of("openid"),
                OAuthClientTrust.CONSENT_REQUIRED, OAuthClientStatus.DISABLED, created.version()));

        assertError(ErrorCode.INVALID_STATE,
                () -> service.rotateSecret(systemAdmin(), "disabled-client"));
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.rotateSecret(systemAdmin(), "missing-client"));
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.find(systemAdmin(), "missing-client"));
    }

    @Test
    void client_id_collision_regenerates_with_a_bounded_unique_candidate() {
        clients.save(OAuthClient.create(
                41L, "collision", "Existing", true, Set.of(CALLBACK), Set.of(),
                Set.of("openid"), OAuthClientTrust.CONSENT_REQUIRED, NOW));
        generator.clientIds.addAll(List.of("collision", "collision", "unique-client"));

        OAuthClientView created = service.create(companyAdmin(41L),
                createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED)).client();

        assertThat(created.clientId()).isEqualTo("unique-client");
        assertThat(clients.findByCompanyId(41L)).extracting(OAuthClient::clientId)
                .containsExactlyInAnyOrder("collision", "unique-client");
    }

    @Test
    void repeated_client_id_collisions_fail_without_overwriting_an_existing_client() {
        clients.save(OAuthClient.create(
                41L, "collision", "Existing", true, Set.of(CALLBACK), Set.of(),
                Set.of("openid"), OAuthClientTrust.CONSENT_REQUIRED, NOW));
        for (int index = 0; index < 20; index++) {
            generator.clientIds.add("collision");
        }

        assertError(ErrorCode.INVALID_STATE, () -> service.create(companyAdmin(41L),
                createCommand("ACME", true, OAuthClientTrust.CONSENT_REQUIRED)));
        assertThat(clients.findByCompanyId(41L)).singleElement()
                .extracting(OAuthClient::displayName).isEqualTo("Existing");
    }

    @Test
    void secure_generator_uses_unpadded_base64url_with_the_required_entropy_sizes() {
        SecureOAuthClientSecretGenerator secure = new SecureOAuthClientSecretGenerator();

        String clientId = secure.generateClientId();
        String secret = secure.generateClientSecret();

        assertThat(clientId).hasSize(43).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(secret).hasSize(64).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(java.util.Base64.getUrlDecoder().decode(clientId)).hasSize(32);
        assertThat(java.util.Base64.getUrlDecoder().decode(secret)).hasSize(48);
    }

    private CreateClient createCommand(
            String companyCode, boolean publicClient, OAuthClientTrust trust) {
        return new CreateClient(
                companyCode, "Payroll RP", publicClient, Set.of(CALLBACK), Set.of(LOGOUT),
                Set.of("openid", "profile"), trust);
    }

    private AuthenticatedAccount companyAdmin(long companyId) {
        return new AuthenticatedAccount(
                101L, companyId, 501L, Set.of(AccountRole.COMPANY_ADMIN), false);
    }

    private AuthenticatedAccount systemAdmin() {
        return new AuthenticatedAccount(
                1L, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false);
    }

    private Company company(long id, String code, CompanyStatus status) {
        return Company.restore(
                id, code, code + " Company", code.toLowerCase() + ".example", status,
                0, NOW, NOW);
    }

    private void register(Company company) {
        when(companies.findByCode(company.code())).thenReturn(Optional.of(company));
        when(companies.findById(company.id())).thenReturn(Optional.of(company));
    }

    private void assertError(ErrorCode expected, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class QueueGenerator implements OAuthClientSecretGenerator {
        private final Queue<String> clientIds = new ArrayDeque<>();
        private final Queue<String> secrets = new ArrayDeque<>();

        @Override
        public String generateClientId() {
            return clientIds.remove();
        }

        @Override
        public String generateClientSecret() {
            return secrets.remove();
        }
    }

    private static final class InMemoryOAuthClientRepository implements OAuthClientRepository {
        private final Map<String, OAuthClient> values = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public OAuthClient save(OAuthClient client) {
            OAuthClient persisted = OAuthClient.restore(
                    client.id() == null ? nextId++ : client.id(), client.companyId(), client.clientId(),
                    client.displayName(), client.status(), client.trust(), client.publicClient(),
                    client.id() == null ? 0 : client.version() + 1,
                    client.redirectUris(), client.postLogoutRedirectUris(), client.scopes(),
                    client.secrets(), client.createdAt(), client.updatedAt());
            values.put(persisted.clientId(), persisted);
            return persisted;
        }

        @Override
        public Optional<OAuthClient> findById(long id) {
            return values.values().stream().filter(client -> client.id() == id).findFirst();
        }

        @Override
        public Optional<OAuthClient> findByClientId(String clientId) {
            return Optional.ofNullable(values.get(clientId));
        }

        @Override
        public List<OAuthClient> findByCompanyId(long companyId) {
            return values.values().stream()
                    .filter(client -> client.companyId() == companyId)
                    .toList();
        }
    }
}
