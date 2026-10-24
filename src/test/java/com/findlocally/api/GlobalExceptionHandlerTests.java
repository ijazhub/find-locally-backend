package com.findlocally.api;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
@Import(GlobalExceptionHandlerTests.Failures.class)
class GlobalExceptionHandlerTests {
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @RestController
    static class Failures {
        @GetMapping("/test/errors/{kind}")
        void fail(@PathVariable String kind) {
            switch (kind) {
                case "domain" -> throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is already completed");
                case "database" -> throw new DataIntegrityViolationException("private SQL and password must not leak");
                case "timeout" -> throw new QueryTimeoutException("private query");
                default -> throw new IllegalStateException("private token must not leak");
            }
        }
    }

    @Test void domainErrorsKeepTheirStatusAndUsefulReason() throws Exception {
        mvc.perform(get("/test/errors/domain")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.detail").value("This booking is already completed"))
                .andExpect(jsonPath("$.instance").value("/test/errors/domain")).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void databaseAndUnexpectedErrorsDoNotExposeInternalDetails() throws Exception {
        mvc.perform(get("/test/errors/database")).andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("private"))));
        mvc.perform(get("/test/errors/timeout")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503)).andExpect(content().string(not(containsString("private"))));
        mvc.perform(get("/test/errors/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(content().string(not(containsString("private"))));
    }
    @Test void malformedJsonAndMissingHeadersHaveSafeProblemBodies() throws Exception {
        mvc.perform(post("/api/v1/bookings").with(user("11111111-1111-1111-1111-111111111111"))
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .contentType("application/json").content("{private token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(content().string(not(containsString("private token"))));
        mvc.perform(post("/api/v1/bookings").with(user("11111111-1111-1111-1111-111111111111"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void securityErrorsUseTheSameProblemFormat() throws Exception {
        mvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/v1/cart").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }
    @Test void invalidFieldsAndPaginationReturnBadRequest() throws Exception {
        mvc.perform(post("/api/v1/bookings").with(user("11111111-1111-1111-1111-111111111111"))
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/api/v1/providers").with(user("11111111-1111-1111-1111-111111111111")).param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }
    @Test void notFoundAndMethodNotAllowedPreserveHttpSemantics() throws Exception {
        mvc.perform(get("/no-such-resource")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(delete("/api/v1/categories").with(user("11111111-1111-1111-1111-111111111111"))).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow")).andExpect(jsonPath("$.status").value(405));
    }
}
