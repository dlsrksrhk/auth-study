package com.sweet.referenceapp.user.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.referenceapp.security.AdminApiHeadersFilter;
import com.sweet.referenceapp.security.CurrentAppUser;
import com.sweet.referenceapp.user.application.*;
import com.sweet.referenceapp.user.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppUserAdminControllerTest {
    private final AppUserAdminService admin = mock(AppUserAdminService.class);
    private final AppUserAdminQueryService queries = mock(AppUserAdminQueryService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final String root = "/bff/admin/users";
    private MockMvc mvc;
    private AppUserView view;

    @BeforeEach void setup() {
        var now = Instant.parse("2026-09-10T00:00:00Z");
        view = new AppUserView(target, "https://issuer.example", "subject", new ExternalUserSnapshot(null,null,null,null,Set.of()),
            AppUserStatus.ACTIVE, Set.of(AppRole.APP_ADMIN,AppRole.APP_USER),now,now,now,3);
        mvc = MockMvcBuilders.standaloneSetup(new AppUserAdminController(admin,queries))
            .setControllerAdvice(new AppUserAdminExceptionHandler()).addFilters(new AdminApiHeadersFilter()).build();
    }

    @Test void detailExposesOnlyContractFieldsWithIsoDatesAndNulls() throws Exception {
        when(queries.detail(target)).thenReturn(view);
        var body = mapper.readTree(mvc.perform(get(root+"/"+target)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertThat(keys(body)).containsExactlyInAnyOrder("id","displayName","email","status","roles","lastLoginAt","version",
            "createdAt","updatedAt","externalIdentity","company","organization","hrRoles");
        assertThat(body.get("roles").toString()).isEqualTo("[\"APP_USER\",\"APP_ADMIN\"]");
        assertThat(body.get("createdAt").asText()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(body.get("lastLoginAt").asText()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(body.get("version").asLong()).isEqualTo(3);
        for (var key : List.of("displayName","email","company","organization")) assertThat(body.get(key).isNull()).isTrue();
        assertThat(keys(body.get("externalIdentity"))).containsExactlyInAnyOrder("issuer","subject");
    }

    @Test void listUsesSummaryAndParsedFilters() throws Exception {
        when(queries.list(new AppUserAdminQuery(1,10,AppUserStatus.DISABLED,AppRole.APP_ADMIN)))
            .thenReturn(new AppUserAdminPage(List.of(view),1,10,11,2));
        var body = mapper.readTree(mvc.perform(get(root).param("page","1").param("size","10").param("status","DISABLED").param("role","APP_ADMIN"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertThat(keys(body)).containsExactlyInAnyOrder("items","page","size","totalElements","totalPages");
        assertThat(keys(body.get("items").get(0))).containsExactlyInAnyOrder("id","displayName","email","status","roles","lastLoginAt","version");
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test void mutationsUseRequestActorAndIgnoreSpoofedActor() throws Exception {
        when(admin.changeStatus(actor,target,AppUserStatus.DISABLED,3)).thenReturn(view);
        when(admin.changeRoles(actor,target,Set.of(AppRole.APP_USER),3)).thenReturn(view);
        for (var operation : List.of("status","roles")) {
            var payload = operation.equals("status") ? "\"status\":\"DISABLED\"" : "\"roles\":[\"APP_USER\",\"APP_USER\"]";
            mvc.perform(put(root+"/"+target+"/"+operation).with(request -> {
                CurrentAppUser.set(request,new AppUserView(actor,view.issuer(),view.subject(),view.snapshot(),view.status(),view.roles(),view.createdAt(),view.updatedAt(),view.lastLoginAt(),3)); return request;
            }).header("actorId",target).contentType(MediaType.APPLICATION_JSON)
                .content("{"+payload+",\"version\":3,\"actorId\":\""+target+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(target.toString())).andExpect(header().string("Cache-Control","no-store"));
        }
        verify(admin).changeStatus(actor,target,AppUserStatus.DISABLED,3);
        verify(admin).changeRoles(actor,target,Set.of(AppRole.APP_USER),3);
    }

    @Test void missingActorIsForbidden() throws Exception {
        mvc.perform(put(root+"/"+target+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\",\"version\":0}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(admin);
    }

    @ParameterizedTest @EnumSource(AppUserAdminException.Code.class)
    void safeDomainErrors(AppUserAdminException.Code code) throws Exception {
        when(queries.detail(target)).thenThrow(new AppUserAdminException(code));
        int expected = switch(code) {
            case INVALID_REQUEST -> 400; case FORBIDDEN -> 403; case APP_USER_NOT_FOUND -> 404;
            case OPTIMISTIC_LOCK_CONFLICT,LAST_ACTIVE_ADMIN_REQUIRED -> 409; case SERVICE_UNAVAILABLE -> 503;
        };
        problem(expected,code.name());
    }

    @Test void databaseErrorsAreSafeAndOptimisticConflictRemains409() throws Exception {
        when(queries.detail(target)).thenThrow(new ObjectOptimisticLockingFailureException("secret SQL token",new RuntimeException("secret")));
        problem(409,"OPTIMISTIC_LOCK_CONFLICT");
        doThrow(new DataAccessResourceFailureException("secret SQL token")).when(queries).detail(target);
        problem(503,"SERVICE_UNAVAILABLE");
        doThrow(new TransactionTimedOutException("secret SQL token")).when(queries).detail(target);
        problem(503,"SERVICE_UNAVAILABLE");
    }

    @Test void transactionCreationFailureReturnsSafeUnavailableProblem() throws Exception {
        when(queries.detail(target)).thenThrow(new CannotCreateTransactionException(
            "secret SQL token", new java.sql.SQLException("secret connection details")));
        problem(503,"SERVICE_UNAVAILABLE");
    }

    @Test void malformedInputDoesNotReachServices() throws Exception {
        mvc.perform(get(root+"/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(root).param("status","")).andExpect(status().isBadRequest());
        for (String body : List.of("{", "", "{\"status\":\"ACTIVE\",\"version\":\"3\"}"))
            mvc.perform(put(root+"/"+target+"/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(admin,queries);
    }

    @Test void methodAndMediaTypeRemainStandardErrors() throws Exception {
        mvc.perform(post(root)).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(put(root+"/"+target+"/status").contentType(MediaType.TEXT_PLAIN).content("secret"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(header().string("Cache-Control","no-store"));
    }

    @Test void unexpectedProgrammingErrorsAreNotHidden() {
        when(queries.detail(target)).thenThrow(new IllegalStateException("programming error"));
        assertThatThrownBy(() -> mvc.perform(get(root+"/"+target))).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test void headerFilterCoversOnlyAdminPathsAndRunsBeforeDownstreamFailure() throws Exception {
        for (String path : List.of("/bff/admin","/bff/admin/","/bff/admin/users","/bff/administrator","/bff/session")) {
            var request = new MockHttpServletRequest("GET",path);
            var response = new MockHttpServletResponse();
            new AdminApiHeadersFilter().doFilter(request,response,(req,res) -> {
                assertThat(response.getHeader("Cache-Control")).isEqualTo(path.equals("/bff/admin") || path.startsWith("/bff/admin/") ? "no-store" : null);
                response.setStatus(403);
            });
        }
    }

    private void problem(int expected,String code) throws Exception {
        var result = mvc.perform(get(root+"/"+target).queryParam("secret","token")).andExpect(status().is(expected))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.type").value("about:blank")).andExpect(jsonPath("$.instance").value(root+"/"+target))
            .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
        assertThat(keys(mapper.readTree(result))).containsExactlyInAnyOrder("type","title","status","detail","instance","code");
        assertThat(result).doesNotContain("secret","SQL","token");
    }
    private Set<String> keys(JsonNode body) { var keys = new HashSet<String>(); body.fieldNames().forEachRemaining(keys::add); return keys; }
}
