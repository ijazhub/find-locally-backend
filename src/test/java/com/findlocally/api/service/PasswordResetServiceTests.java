package com.findlocally.api.service;

import com.findlocally.api.model.ForgotPasswordRequest;
import com.findlocally.api.model.ResetPasswordRequest;
import com.findlocally.api.repository.AuthRepository;
import com.findlocally.api.utility.AuthTokens;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTests {
    private final AuthRepository repository = mock(AuthRepository.class);
    private final PasswordResetSender sender = mock(PasswordResetSender.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private final AuthTokens tokens = new AuthTokens(clock, "");
    private final PasswordResetService service = new PasswordResetService(repository, tokens, sender, clock);
    private final UUID user = UUID.randomUUID();
    private final String token = tokens.refresh();
    private final OffsetDateTime now = OffsetDateTime.now(clock);

    @Test
    void requestsSendOnlyToExistingPasswordAccountsAndStoreOnlyTheHash() {
        when(repository.lockCredential("asha@example.com")).thenReturn(new AuthRepository.Credential(user, "hash", null));
        when(sender.send(anyString(), anyString())).thenReturn(true);
        service.request(new ForgotPasswordRequest().email(" ASHA@EXAMPLE.COM "));
        var raw = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("asha@example.com"), raw.capture());
        assertTrue(raw.getValue().matches("[A-Za-z0-9_-]{43}"));
        verify(repository).createReset(user, "asha@example.com", tokens.hash(raw.getValue()), now, now.plusMinutes(15));
        service.request(new ForgotPasswordRequest().email("unknown@example.com"));
        verify(sender, times(1)).send(anyString(), anyString());
    }

    @Test
    void throttledRequestsDoNotSendOrCreateTokensAndDeliveryFailureInvalidatesToken() {
        when(repository.lockCredential("asha@example.com")).thenReturn(new AuthRepository.Credential(user, "hash", null));
        when(repository.resetThrottled(user, now)).thenReturn(true);
        service.request(new ForgotPasswordRequest().email("asha@example.com"));
        verify(sender, never()).send(anyString(), anyString());
        verify(repository, never()).createReset(any(), anyString(), anyString(), any(), any());
        when(repository.resetThrottled(user, now)).thenReturn(false);
        when(sender.send(anyString(), anyString())).thenReturn(false);
        service.request(new ForgotPasswordRequest().email("asha@example.com"));
        verify(repository, times(2)).invalidateResets(user, now);
    }

    @Test
    void resettingHashesPasswordConsumesAllTokensAndRevokesEverySession() {
        when(repository.findReset(tokens.hash(token))).thenReturn(new AuthRepository.PasswordReset(user, "asha@example.com", now.plusMinutes(15), null));
        when(repository.lockPasswordAccount(user)).thenReturn(new AuthRepository.PasswordAccount(user, "asha@example.com", "old-hash"));
        service.reset(new ResetPasswordRequest().token(token).newPassword("a new strong password"));
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).changePassword(eq(user), hash.capture());
        assertTrue(new BCryptPasswordEncoder().matches("a new strong password", hash.getValue()));
        verify(repository).invalidateResets(user, now);
        verify(repository).revokeAll(user, now);
    }

    @Test
    void rejectsMissingExpiredConsumedAndEmailChangedTokensWithoutChangingPassword() {
        var request = new ResetPasswordRequest().token(token).newPassword("a new strong password");
        assertThrows(ResponseStatusException.class, () -> service.reset(request));
        when(repository.lockPasswordAccount(user)).thenReturn(new AuthRepository.PasswordAccount(user, "asha@example.com", "hash"));
        for (var reset : new AuthRepository.PasswordReset[] {
                new AuthRepository.PasswordReset(user, "asha@example.com", now, null),
                new AuthRepository.PasswordReset(user, "asha@example.com", now.plusMinutes(15), now),
                new AuthRepository.PasswordReset(user, "old@example.com", now.plusMinutes(15), null)}) {
            when(repository.findReset(tokens.hash(token))).thenReturn(reset);
            assertThrows(ResponseStatusException.class, () -> service.reset(request));
        }
        assertThrows(ResponseStatusException.class,
                () -> service.reset(new ResetPasswordRequest().token(token).newPassword("é".repeat(37))));
        verify(repository, never()).changePassword(any(), any());
        verify(repository, never()).revokeAll(any(), any());
    }
}
