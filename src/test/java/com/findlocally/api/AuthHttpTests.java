package com.findlocally.api;

import com.findlocally.api.model.AuthResponse;
import com.findlocally.api.service.AuthService;
import com.findlocally.api.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
class AuthHttpTests {
    @Autowired WebApplicationContext context;
    @MockitoBean AuthService auth;
    @MockitoBean PasswordResetService passwordReset;
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private MockMvc mvc;
    private static final String BODY = "{\"email\":\"asha@example.com\",\"password\":\"a strong password\"}";

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void passwordRecoveryEndpointsArePublicAndDoNotReturnSecrets() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json")
                        .content("{\"email\":\"asha@example.com\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json")
                        .content("{\"token\":\"" + "A".repeat(43) + "\",\"newPassword\":\"a strong new password\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(passwordReset).request(any());
        verify(passwordReset).reset(any());
    }

    @Test
    void invalidRecoveryInputIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"email\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json")
                        .content("{\"token\":\"bad\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(passwordReset);
    }

    @Test
    void registrationAndLoginArePublicAndTokenResponsesAreNotCached() throws Exception {
        when(auth.register(any())).thenReturn(new AuthResponse().accessToken("jwt").refreshToken("refresh").newUser(true));
        when(auth.login(any())).thenReturn(new AuthResponse().accessToken("jwt").refreshToken("refresh").newUser(false));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").value("jwt"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newUser").value(false));
    }

    @Test
    void invalidInputIsRejectedBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{\"email\":\"asha@example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    void invalidCredentialsAndUnlistedAuthRoutesRequireAuthentication() throws Exception {
        when(auth.login(any())).thenThrow(new AuthService.InvalidCredentialsException());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/otp/request").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/otp/verify").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
