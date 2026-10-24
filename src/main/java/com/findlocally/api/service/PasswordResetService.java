package com.findlocally.api.service;

import com.findlocally.api.model.ForgotPasswordRequest;
import com.findlocally.api.model.ResetPasswordRequest;
import com.findlocally.api.repository.AuthRepository;
import com.findlocally.api.utility.AuthTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {
    private final AuthRepository repository;
    private final AuthTokens tokens;
    private final PasswordResetSender sender;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public PasswordResetService(AuthRepository repository, AuthTokens tokens, PasswordResetSender sender, Clock clock) {
        this.repository = repository;
        this.tokens = tokens;
        this.sender = sender;
        this.clock = clock;
    }

    @Transactional
    public void request(ForgotPasswordRequest request) {
        if (request == null) throw bad("Email is required");
        String email = AuthService.email(request.getEmail());
        // Configuration errors are independent of whether the account exists.
        sender.requireConfigured();
        var account = repository.lockCredential(email);
        if (account == null || account.hash() == null) return;
        var now = OffsetDateTime.now(clock);
        if (repository.resetThrottled(account.id(), now)) return;
        String token = tokens.refresh();
        repository.invalidateResets(account.id(), now);
        repository.createReset(account.id(), email, tokens.hash(token), now, now.plusMinutes(15));
        if (!sender.send(email, token)) repository.invalidateResets(account.id(), now);
    }

    @Transactional
    public void reset(ResetPasswordRequest request) {
        if (request == null || request.getToken() == null || !request.getToken().matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String password = request.getNewPassword();
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw bad("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
        String hash = tokens.hash(request.getToken());
        var initial = repository.findReset(hash);
        if (initial == null) throw invalid();
        // Login, refresh and reset serialize on the user before touching session/token rows.
        var account = repository.lockPasswordAccount(initial.user());
        var reset = repository.findReset(hash);
        var now = OffsetDateTime.now(clock);
        if (account == null || account.hash() == null || reset == null || reset.consumedAt() != null
                || !reset.expiresAt().isAfter(now) || !reset.email().equals(account.email())) throw invalid();
        repository.changePassword(account.id(), passwords.encode(password));
        repository.invalidateResets(account.id(), now);
        repository.revokeAll(account.id(), now);
    }

    private ResponseStatusException invalid() { return bad("Invalid or expired password reset token"); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
