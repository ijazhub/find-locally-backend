package com.findlocally.api;

import com.findlocally.api.repository.AuthRepository;
import com.findlocally.api.utility.AuthTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
class ApiSecurityTests {
    @Autowired WebApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired AuthTokens tokens;
    @Autowired Clock clock;
    @Value("${app.auth.jwt-secret}") String secret;
    @MockitoBean AuthRepository sessions;
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @MockitoBean org.springframework.transaction.PlatformTransactionManager transactions;
    private MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void everyMappedApplicationOperationExceptTokenAcquisitionRequiresAuthentication() throws Exception {
        Set<String> publicPosts = Set.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password");
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().equals("com.findlocally.api.controller")) continue;
            for (String pattern : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    if (method.name().equals("POST") && publicPosts.contains(pattern)) continue;
                    String path = pattern.replaceAll("\\{[^}]+}", "11111111-1111-1111-1111-111111111111");
                    mvc.perform(request(HttpMethod.valueOf(method.name()), path))
                            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
                    checked++;
                }
            }
        }
        assertTrue(checked > 50, "Guard against losing application endpoint coverage");
        mvc.perform(get("/api/v2/future-route")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
    }

    @Test
    void onlyExplicitPostAuthEndpointsAreExemptAndCorsPreflightWorks() throws Exception {
        for (String path : List.of("register", "login", "refresh", "forgot-password", "reset-password")) {
            mvc.perform(post("/api/v1/auth/" + path).contentType("application/json")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/auth/" + path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(options("/api/v1/providers").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    @Test
    void invalidSignaturesMalformedAndExpiredTokensFailBeforeSessionLookup() throws Exception {
        UUID user = UUID.randomUUID(), session = UUID.randomUUID();
        String expired = new AuthTokens(Clock.offset(clock, Duration.ofMinutes(-16)), secret).access(user, session);
        String otherSignature = new AuthTokens(clock, "").access(user, session);
        for (String token : List.of("invalid", expired, otherSignature)) {
            mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void validJwtRequiresAnActiveSessionAndProtectsFormerlyPublicCatalog() throws Exception {
        UUID user = UUID.randomUUID(), session = UUID.randomUUID();
        String token = tokens.access(user, session);
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        when(sessions.activeSession(eq(session), eq(user), any())).thenReturn(true);
        when(sessions.roles(user)).thenReturn(List.of("CUSTOMER"));
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        when(sessions.activeSession(eq(session), eq(user), any())).thenReturn(false);
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
