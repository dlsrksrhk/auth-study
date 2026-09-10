package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminQuery;
import com.sweet.referenceapp.user.application.AppUserAdminQueryService;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import com.sweet.referenceapp.user.infrastructure.AppUserRepositoryAdapter;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

class AppUserAdminQueryIntegrationTest extends BootstrapIntegrationSupport {
    private static final Instant SAME_TIME = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTIVE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DISABLED = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired AppUserAdminQueryService queries;
    @Autowired AppUserRepositoryAdapter repository;

    @BeforeEach
    void users() {
        insert(ADMIN, "admin", AppUserStatus.ACTIVE, SAME_TIME);
        insert(ACTIVE, "active", AppUserStatus.ACTIVE, SAME_TIME);
        insert(DISABLED, "disabled", AppUserStatus.DISABLED, SAME_TIME.minusSeconds(1));
        role(ADMIN, AppRole.APP_ADMIN);
    }

    @Test
    void filtersStatusAndRoleWithAndSemanticsAndCountsUsersOnce() {
        role(ADMIN, AppRole.APP_ADMIN);
        var result = queries.list(new AppUserAdminQuery(0, 1, AppUserStatus.ACTIVE, AppRole.APP_ADMIN));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(ADMIN);
        assertThat(result.items().getFirst().roles()).contains(AppRole.APP_ADMIN);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void ordersByCreatedAtDescendingThenIdAscendingAndPaginates() {
        var first = queries.list(new AppUserAdminQuery(0, 2, null, null));
        assertThat(first.items()).extracting(item -> item.id()).containsExactly(ADMIN, ACTIVE);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(queries.list(new AppUserAdminQuery(1, 2, null, null)).items())
                .extracting(item -> item.id()).containsExactly(DISABLED);
    }

    @Test
    void returnsEmptyItemsForEmptyAndOutOfRangePagesWithoutLosingTotals() {
        var none = queries.list(new AppUserAdminQuery(0, 10, null, AppRole.APP_ADMIN));
        assertThat(none.totalElements()).isEqualTo(1);
        var outside = queries.list(new AppUserAdminQuery(10, 2, null, null));
        assertThat(outside.items()).isEmpty();
        assertThat(outside.totalElements()).isEqualTo(3);
        assertThat(outside.totalPages()).isEqualTo(2);
        assertThat(queries.list(new AppUserAdminQuery(Integer.MAX_VALUE, 100, null, null)).items()).isEmpty();
    }

    @Test
    void validatesPageAndSize() {
        for (var invalid : List.of(new int[] {-1, 1}, new int[] {0, 0}, new int[] {0, 101})) {
            assertThatThrownBy(() -> new AppUserAdminQuery(invalid[0], invalid[1], null, null))
                    .isInstanceOfSatisfying(AppUserAdminException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(AppUserAdminException.Code.INVALID_REQUEST));
        }
    }

    @Test
    void returnsDetailWithRolesAndRejectsMissingUser() {
        assertThat(queries.detail(ADMIN).roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThatThrownBy(() -> queries.detail(UUID.randomUUID()))
                .isInstanceOfSatisfying(AppUserAdminException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(AppUserAdminException.Code.APP_USER_NOT_FOUND));
    }

    @Test
    void countAndItemsUseOneRepeatableReadSnapshot() throws Exception {
        var idsQueryReached = new CountDownLatch(1);
        var insertCommitted = new CountDownLatch(1);
        var original = (EntityManager) ReflectionTestUtils.getField(repository, "entityManager");
        var entityManager = spy(original);
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("select u.id from app_user")) {
                idsQueryReached.countDown();
                assertThat(insertCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(entityManager).createNativeQuery(anyString());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstCall = executor.submit(() -> queries.list(new AppUserAdminQuery(0, 10, null, null)));
            assertThat(idsQueryReached.await(5, TimeUnit.SECONDS)).isTrue();
            insert(UUID.fromString("00000000-0000-0000-0000-000000000004"), "committed-between",
                    AppUserStatus.ACTIVE, SAME_TIME.plusSeconds(1));
            insertCommitted.countDown();
            var first = firstCall.get(5, TimeUnit.SECONDS);
            assertThat(first.totalElements()).isEqualTo(3);
            assertThat(first.items()).hasSize(3);
        } finally {
            ReflectionTestUtils.setField(repository, "entityManager", original);
        }
        var next = queries.list(new AppUserAdminQuery(0, 10, null, null));
        assertThat(next.totalElements()).isEqualTo(4);
        assertThat(next.items()).hasSize(4);
    }

    private void insert(UUID id, String subject, AppUserStatus status, Instant createdAt) {
        jdbc.update("""
                insert into app_user(id,issuer,subject,email,display_name,hr_roles_snapshot,status,
                  created_at,updated_at,last_login_at,version)
                values (?, 'http://idp.localhost:8080', ?, ?, ?, cast('[]' as jsonb), ?, ?, ?, ?, 0)
                """, id, subject, subject + "@example.test", subject, status.name(),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        role(id, AppRole.APP_USER);
    }

    private void role(UUID id, AppRole role) {
        jdbc.update("insert into app_user_role(app_user_id,role) values (?,?) on conflict do nothing", id, role.name());
    }
}
