package com.findlocally.api.service;

import com.findlocally.api.model.LoginRequest;
import com.findlocally.api.model.RegisterRequest;
import com.findlocally.api.model.UserProfile;
import com.findlocally.api.repository.AccountRepository;
import com.findlocally.api.repository.AuthRepository;
import com.findlocally.api.utility.AuthTokens;
import com.findlocally.api.utility.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTests {
    private final AuthRepository repository = mock(AuthRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private final AuthTokens tokens = new AuthTokens(clock, "");
    private final AuthService service = new AuthService(repository, accounts, tokens, mock(CurrentUser.class), clock);
    private final UUID user = UUID.randomUUID();
    private static final String PASSWORD = "a strong password";

    @Test
    void registrationHashesPasswordNormalizesEmailAndIssuesValidJwt() {
        when(repository.register(eq("asha@example.com"), anyString(), eq("Asha"))).thenReturn(user);
        when(accounts.profile(user)).thenReturn(new UserProfile().id(user));
        var response = service.register(new RegisterRequest().email(" ASHA@example.com ").password(PASSWORD).name("Asha"));
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).register(eq("asha@example.com"), hash.capture(), eq("Asha"));
        assertNotEquals(PASSWORD, hash.getValue());
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD, hash.getValue()));
        assertEquals(user.toString(), tokens.decode(response.getAccessToken()).getSubject());
        assertTrue(response.getNewUser());
        verify(repository).createRefresh(any(), eq(user), eq(tokens.hash(response.getRefreshToken())), any(), any());
    }

    @Test
    void wrongMissingAndLockedCredentialsDoNotIssueTokens() {
        var request = new LoginRequest().email("asha@example.com").password(PASSWORD);
        assertThrows(AuthService.InvalidCredentialsException.class, () -> service.login(request));
        when(repository.lockCredential("asha@example.com")).thenReturn(new AuthRepository.Credential(user,
                new BCryptPasswordEncoder().encode("different password"), null));
        assertThrows(AuthService.InvalidCredentialsException.class, () -> service.login(request));
        verify(repository).failedLogin(eq(user), any());
        when(repository.lockCredential("asha@example.com")).thenReturn(new AuthRepository.Credential(user,
                new BCryptPasswordEncoder().encode(PASSWORD), OffsetDateTime.now(clock).plusMinutes(15)));
        assertThrows(AuthService.InvalidCredentialsException.class, () -> service.login(request));
        verify(repository, never()).createRefresh(any(), any(), any(), any(), any());
    }

    @Test
    void validPasswordLogsInAndClearsFailures() {
        when(repository.lockCredential("asha@example.com")).thenReturn(new AuthRepository.Credential(user,
                new BCryptPasswordEncoder().encode(PASSWORD), null));
        when(accounts.profile(user)).thenReturn(new UserProfile().id(user));
        var response = service.login(new LoginRequest().email("ASHA@example.com").password(PASSWORD));
        assertFalse(response.getNewUser());
        assertEquals(user.toString(), tokens.decode(response.getAccessToken()).getSubject());
        verify(repository).clearFailures(user);
    }

    @Test
    void rejectsInvalidInputAndDuplicateRegistration() {
        for (String password : new String[] {"short", "é".repeat(37)}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.register(new RegisterRequest().email("asha@example.com").password(password))).getStatusCode());
        }
        assertThrows(ResponseStatusException.class,
                () -> service.register(new RegisterRequest().email("invalid").password(PASSWORD)));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.register(new RegisterRequest().email("asha@example.com").password(PASSWORD))).getStatusCode());
    }
}
