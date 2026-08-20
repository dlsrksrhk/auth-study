package com.sweet.authstudy.shared.error;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ErrorContractIntegrationTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, ErrorContractIntegrationTest.ProbeController.class})
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
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
    }

    @RestController
    public static class ProbeController {

        @PostMapping("/probe")
        public ResponseEntity<Void> probe(@Valid @RequestBody ProbeRequest request) {
            return ResponseEntity.noContent().build();
        }
    }

    public record ProbeRequest(@NotBlank String code) {
    }
}
