package com.sweet.referenceapp.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.domain.AppRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AppUserAdminRequestsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"status\":1,\"version\":0}",
        "{\"status\":null,\"version\":0}", "{\"status\":\"active\",\"version\":0}",
        "{\"status\":\"ACTIVE\"}", "{\"status\":\"ACTIVE\",\"version\":null}",
        "{\"status\":\"ACTIVE\",\"version\":\"3\"}", "{\"status\":\"ACTIVE\",\"version\":3.0}",
        "{\"status\":\"ACTIVE\",\"version\":-1}", "{\"status\":\"ACTIVE\",\"version\":9223372036854775808}"})
    void rejectsInvalidStatusBody(String json) throws Exception {
        var body = mapper.readTree(json);
        assertThatThrownBy(() -> AppUserAdminRequests.status(body)).isInstanceOfSatisfying(
            AppUserAdminException.class, e -> assertThat(e.code()).isEqualTo(AppUserAdminException.Code.INVALID_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"roles\":null,\"version\":0}",
        "{\"roles\":\"APP_USER\",\"version\":0}", "{\"roles\":[],\"version\":0}",
        "{\"roles\":[\"APP_ADMIN\"],\"version\":0}", "{\"roles\":[null],\"version\":0}",
        "{\"roles\":[1],\"version\":0}", "{\"roles\":[\"APP_USER\",\"OTHER\"],\"version\":0}",
        "{\"roles\":[\"APP_USER\"],\"version\":false}"})
    void rejectsInvalidRolesBody(String json) throws Exception {
        var body = mapper.readTree(json);
        assertThatThrownBy(() -> AppUserAdminRequests.roles(body)).isInstanceOf(AppUserAdminException.class);
    }

    @Test void acceptsStrictValuesAndNormalizesDuplicates() throws Exception {
        assertThat(AppUserAdminRequests.status(mapper.readTree("{\"status\":\"DISABLED\",\"version\":9223372036854775807}")).version()).isEqualTo(Long.MAX_VALUE);
        assertThat(AppUserAdminRequests.roles(mapper.readTree("{\"roles\":[\"APP_USER\",\"APP_ADMIN\",\"APP_USER\"],\"version\":3}")).roles())
            .containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
    }

    @Test void defaultsAndFilters() {
        var defaults = AppUserAdminRequests.query(null, null, null, null);
        assertThat(defaults.page()).isZero();
        assertThat(defaults.size()).isEqualTo(20);
        var filtered = AppUserAdminRequests.query("2147483647", "100", "DISABLED", "APP_ADMIN");
        assertThat(filtered.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(filtered.role()).isEqualTo(AppRole.APP_ADMIN);
    }

    @ParameterizedTest @ValueSource(strings={"", "-1", "1.0", "2147483648", " 1", "abc"})
    void rejectsInvalidPage(String page) {
        assertThatThrownBy(() -> AppUserAdminRequests.query(page,null,null,null)).isInstanceOf(AppUserAdminException.class);
    }
    @ParameterizedTest @ValueSource(strings={"", "0", "101", "-1", "1.0", "999999999999"})
    void rejectsInvalidSize(String size) {
        assertThatThrownBy(() -> AppUserAdminRequests.query(null,size,null,null)).isInstanceOf(AppUserAdminException.class);
    }
    @ParameterizedTest @ValueSource(strings={"", "unknown", "1", " ACTIVE"})
    void rejectsInvalidEnums(String value) {
        assertThatThrownBy(() -> AppUserAdminRequests.query(null,null,value,null)).isInstanceOf(AppUserAdminException.class);
        assertThatThrownBy(() -> AppUserAdminRequests.query(null,null,null,value)).isInstanceOf(AppUserAdminException.class);
    }
}
