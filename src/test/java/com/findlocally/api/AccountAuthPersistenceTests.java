package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.AccountService;
import com.findlocally.api.service.AuthService;
import com.findlocally.api.service.PasswordResetService;
import com.findlocally.api.service.PasswordResetSender;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "services.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never", "app.media.public-base-url=https://media.example.com/"})
@Transactional
class AccountAuthPersistenceTests extends TemporaryDatabase {
    @Autowired AuthService auth;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper mapper;
    @Autowired Clock clock;
    @Autowired WebApplicationContext context;
    @Autowired PasswordResetService passwordReset;
    @MockitoBean PasswordResetSender resetSender;

    private MockMvc mvc;

    @BeforeEach
    void mockMvc() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    private AuthResponse login() {
        return auth.register(new RegisterRequest().email("asha@example.com").password("a strong password").name("Asha"));
    }

    private String resetLink() {
        when(resetSender.send(anyString(), anyString())).thenReturn(true);
        passwordReset.request(new ForgotPasswordRequest().email("asha@example.com"));
        var token = ArgumentCaptor.forClass(String.class);
        verify(resetSender, atLeastOnce()).send(eq("asha@example.com"), token.capture());
        return token.getValue();
    }

    @Test
    void passwordResetIsPublicSingleUseAndRevokesAllSessions() throws Exception {
        var registered = login();
        var second = auth.login(new LoginRequest().email("asha@example.com").password("a strong password"));
        String token = resetLink();
        assertNotEquals(token, jdbc.queryForObject("SELECT token_hash FROM pg_temp.password_reset_tokens", String.class));
        assertEquals(64, jdbc.queryForObject("SELECT length(token_hash) FROM pg_temp.password_reset_tokens", Integer.class));
        jdbc.update("UPDATE pg_temp.users SET failed_login_attempts = 5, locked_until = ?", OffsetDateTime.now(clock).plusMinutes(15));
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json")
                        .content(mapper.writeValueAsString(new ResetPasswordRequest().token(token).newPassword("a strong new password"))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_attempts FROM pg_temp.users", Integer.class));
        assertNull(jdbc.queryForObject("SELECT locked_until FROM pg_temp.users", OffsetDateTime.class));
        for (var session : java.util.List.of(registered, second)) {
            mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + session.getAccessToken()))
                    .andExpect(status().isUnauthorized());
            assertThrows(ResponseStatusException.class,
                    () -> nested(() -> auth.refreshToken(new RefreshTokenRequest().refreshToken(session.getRefreshToken()))));
        }
        assertThrows(AuthService.InvalidCredentialsException.class,
                () -> auth.login(new LoginRequest().email("asha@example.com").password("a strong password")));
        assertNotNull(auth.login(new LoginRequest().email("asha@example.com").password("a strong new password")).getAccessToken());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> nested(() -> passwordReset.reset(new ResetPasswordRequest().token(token).newPassword("another strong password")))).getStatusCode());
    }

    @Test
    void passwordResetRequestsAreGenericThrottledAndResendsInvalidateEarlierTokens() throws Exception {
        login();
        mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        verify(resetSender, never()).send(anyString(), anyString());
        String first = resetLink();
        passwordReset.request(new ForgotPasswordRequest().email("ASHA@example.com"));
        verify(resetSender, times(1)).send(anyString(), anyString());
        jdbc.update("UPDATE pg_temp.password_reset_tokens SET created_at = ?", OffsetDateTime.now(clock).minusSeconds(61));
        String second = resetLink();
        assertNotEquals(first, second);
        assertThrows(ResponseStatusException.class,
                () -> nested(() -> passwordReset.reset(new ResetPasswordRequest().token(first).newPassword("a strong new password"))));
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE pg_temp.password_reset_tokens SET created_at = ?", OffsetDateTime.now(clock).minusSeconds(61));
            passwordReset.request(new ForgotPasswordRequest().email("asha@example.com"));
        }
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM pg_temp.password_reset_tokens", Integer.class));
        verify(resetSender, times(5)).send(anyString(), anyString());
    }

    @Test
    void expiredResetTokensEmailChangesAndDeliveryFailureCannotChangePassword() {
        login();
        String token = resetLink();
        jdbc.update("UPDATE pg_temp.password_reset_tokens SET expires_at = ?", OffsetDateTime.now(clock).minusSeconds(1));
        assertThrows(ResponseStatusException.class,
                () -> nested(() -> passwordReset.reset(new ResetPasswordRequest().token(token).newPassword("a strong new password"))));
        jdbc.update("UPDATE pg_temp.password_reset_tokens SET expires_at = ?", OffsetDateTime.now(clock).plusMinutes(15));
        jdbc.update("UPDATE pg_temp.users SET email = 'changed@example.com'");
        assertThrows(ResponseStatusException.class,
                () -> nested(() -> passwordReset.reset(new ResetPasswordRequest().token(token).newPassword("a strong new password"))));
        jdbc.update("UPDATE pg_temp.users SET email = 'asha@example.com'");
        jdbc.update("UPDATE pg_temp.password_reset_tokens SET created_at = ?", OffsetDateTime.now(clock).minusSeconds(61));
        when(resetSender.send(anyString(), anyString())).thenReturn(false);
        passwordReset.request(new ForgotPasswordRequest().email("asha@example.com"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.password_reset_tokens WHERE consumed_at IS NULL", Integer.class));
        assertNotNull(auth.login(new LoginRequest().email("asha@example.com").password("a strong password")).getAccessToken());
    }

    private void signIn(UUID user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.toString(), null, java.util.List.of()));
    }

    @Test
    void loginAuthenticatesAccountAndLogoutRevokesBothTokens() throws Exception {
        var response = login();
        assertTrue(response.getNewUser());
        assertEquals(java.util.List.of(UserProfile.RolesEnum.CUSTOMER), response.getUser().getRoles());
        assertNotEquals(response.getRefreshToken(), jdbc.queryForObject("SELECT token_hash FROM pg_temp.refresh_tokens", String.class));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + response.getAccessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Asha"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + response.getAccessToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + response.getAccessToken()))
                .andExpect(status().isUnauthorized());
        var failure = assertThrows(ResponseStatusException.class,
                () -> nested(() -> auth.refreshToken(new RefreshTokenRequest().refreshToken(response.getRefreshToken()))));
        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
    }

    @Test
    void rotatesRefreshTokenAndRejectsOldAccessAndRefresh() throws Exception {
        var login = login();
        var next = auth.refreshToken(new RefreshTokenRequest().refreshToken(login.getRefreshToken()));
        assertNotEquals(login.getRefreshToken(), next.getRefreshToken());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + login.getAccessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + next.getAccessToken()))
                .andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,
                () -> nested(() -> auth.refreshToken(new RefreshTokenRequest().refreshToken(login.getRefreshToken()))));
    }

    @Test
    void passwordLoginNormalizesEmailAndRejectsDuplicatesAndInvalidCredentials() throws Exception {
        var registered = login();
        String hash = jdbc.queryForObject("SELECT password_hash FROM pg_temp.users", String.class);
        assertNotEquals("a strong password", hash);
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("a strong password", hash));
        var signedIn = auth.login(new LoginRequest().email(" ASHA@EXAMPLE.COM ").password("a strong password"));
        assertFalse(signedIn.getNewUser());
        assertEquals(registered.getUser().getId(), signedIn.getUser().getId());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> nested(() -> auth.register(new RegisterRequest().email("ASHA@example.com").password("a strong password")))).getStatusCode());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"asha@example.com\",\"password\":\"a strong password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        mvc.perform(post("/api/v1/auth/otp/request").header("Authorization", "Bearer " + signedIn.getAccessToken())
                .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedPasswordAttemptsCommitAndLockAccountUntilExpiry() {
        login();
        for (int i = 0; i < 5; i++) {
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            tx.executeWithoutResult(status -> {
                assertThrows(AuthService.InvalidCredentialsException.class,
                        () -> auth.login(new LoginRequest().email("asha@example.com").password("wrong password")));
                assertFalse(status.isRollbackOnly());
            });
        }
        assertEquals(5, jdbc.queryForObject("SELECT failed_login_attempts FROM pg_temp.users", Integer.class));
        assertThrows(AuthService.InvalidCredentialsException.class,
                () -> auth.login(new LoginRequest().email("asha@example.com").password("a strong password")));
        jdbc.update("UPDATE pg_temp.users SET locked_until = ?", OffsetDateTime.now(clock).minusSeconds(1));
        assertNotNull(auth.login(new LoginRequest().email("asha@example.com").password("a strong password")).getAccessToken());
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_attempts FROM pg_temp.users", Integer.class));
    }

    @Test
    void profilePatchAndDefaultAddressesAreScopedToTheUser() {
        var login = login();
        UUID user = login.getUser().getId();
        signIn(user);
        accounts.updateMe(new UserUpdate().email("asha@example.com"));
        assertEquals("Asha", accounts.getMe().getName());
        assertEquals("asha@example.com", accounts.getMe().getEmail());
        var first = accounts.createAddress(address("First").isDefault(true));
        var second = accounts.createAddress(address("Second").isDefault(true));
        assertEquals(second.getId(), accounts.listAddresses().getFirst().getId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.addresses WHERE is_default", Integer.class));
        accounts.updateAddress(first.getId(), address("Changed").isDefault(true));
        assertEquals(first.getId(), accounts.listAddresses().getFirst().getId());
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.users (id, phone) VALUES (?, '+919876543211')", other);
        signIn(other);
        assertThrows(ResponseStatusException.class, () -> nested(() -> accounts.updateAddress(first.getId(), address("Foreign").isDefault(true))));
        assertThrows(ResponseStatusException.class, () -> nested(() -> accounts.deleteAddress(second.getId())));
        assertTrue(accounts.listAddresses().isEmpty());
        signIn(user);
        accounts.deleteAddress(second.getId());
        assertEquals(1, accounts.listAddresses().size());
    }

    @Test
    void avatarRequiresAnOwnedCompletedUploadAndUsesPublicUrl() {
        UUID user = login().getUser().getId();
        signIn(user);
        UUID upload = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pg_temp.uploads (id, user_id, purpose, content_type, size_bytes, object_key, status)
                VALUES (?, ?, 'AVATAR', 'image/png', 10, 'avatars/my photo.png', 'UPLOADED')
                """, upload, user);
        assertEquals("https://media.example.com/avatars/my%20photo.png",
                accounts.updateMe(new UserUpdate().avatarUploadId(upload)).getAvatarUrl().toString());
        assertThrows(ResponseStatusException.class, () -> nested(() -> accounts.updateMe(new UserUpdate().avatarUploadId(UUID.randomUUID()))));
    }

    private AddressInput address(String line) { return new AddressInput().line1(line).city("Pune").pincode("411001"); }
}
