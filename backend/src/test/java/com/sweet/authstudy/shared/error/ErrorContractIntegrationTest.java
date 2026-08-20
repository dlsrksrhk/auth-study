package com.sweet.authstudy.shared.error;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ErrorContractIntegrationTest.ProbeController.class)
@Import({ApiProblemFactory.class, GlobalExceptionHandler.class, ErrorContractIntegrationTest.ProbeController.class})
@AutoConfigureMockMvc(addFilters = false)
class ErrorContractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void validation_error_contains_machine_code_and_field_errors() throws Exception {
        mvc.perform(post("/probe")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Invalid request value."));
    }

    @Test
    void unique_constraint_violation_returns_duplicate_conflict_without_database_detail() throws Exception {
        mvc.perform(get("/probe/unique-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"))
                .andExpect(jsonPath("$.detail").value("A resource with the same unique value already exists."));
    }

    @Test
    void foreign_key_constraint_violation_returns_safe_internal_error() throws Exception {
        mvc.perform(get("/probe/foreign-key-conflict"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void api_exception_does_not_expose_opaque_exception_detail() throws Exception {
        mvc.perform(get("/probe/api-error"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.detail").value("The request is not valid for the current resource state."));
    }

    @RestController
    public static class ProbeController {

        @PostMapping("/probe")
        public ResponseEntity<Void> probe(@Valid @RequestBody ProbeRequest request) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/probe/unique-conflict")
        public void uniqueConflict() {
            throw new DataIntegrityViolationException(
                    "database detail must not be exposed", new SQLException("duplicate key", "23505"));
        }

        @GetMapping("/probe/foreign-key-conflict")
        public void foreignKeyConflict() {
            throw new DataIntegrityViolationException(
                    "database detail must not be exposed", new SQLException("foreign key", "23503"));
        }

        @GetMapping("/probe/api-error")
        public void apiError() {
            throw new ApiException(ErrorCode.INVALID_STATE, "opaque-secret-value");
        }
    }

    public record ProbeRequest(@NotBlank(message = "opaque-rejected-value") String code) {
    }
}
