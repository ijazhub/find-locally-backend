package com.findlocally.api.service;

import com.findlocally.api.repository.AuthRepository;
import com.findlocally.api.repository.AccountRepository;
import com.findlocally.api.model.*;
import com.findlocally.api.utility.AuthTokens;
import com.findlocally.api.utility.CurrentUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final AccountRepository accounts;
    private final AuthTokens tokens;

    private final CurrentUser currentUser;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public AuthService(AuthRepository repository, AccountRepository accounts, AuthTokens tokens,
            CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.tokens = tokens;

        this.currentUser = currentUser;
        this.clock = clock;
    }

    private final String dummyHash = passwords.encode("unknown-account-password");

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request == null) throw badRequest("Email and password are required");
        String email = email(request.getEmail());
        password(request.getPassword());
        if (request.getName() != null && request.getName().length() > 120) throw badRequest("Name is too long");
        UUID user = repository.register(email, passwords.encode(request.getPassword()), request.getName());
        if (user == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        return response(user, true);
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthResponse login(LoginRequest request) {
        if (request == null) throw badRequest("Email and password are required");
        String email = email(request.getEmail());
        password(request.getPassword());
        var credential = repository.lockCredential(email);
        boolean matches = passwords.matches(request.getPassword(), credential == null || credential.hash() == null ? dummyHash : credential.hash());
        var now = OffsetDateTime.now(clock);
        if (credential == null || credential.hash() == null) throw new InvalidCredentialsException();
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) throw new InvalidCredentialsException();
        if (!matches) {
            repository.failedLogin(credential.id(), now);
            throw new InvalidCredentialsException();
        }
        repository.clearFailures(credential.id());
        return response(credential.id(), false);
    }

    private AuthResponse response(UUID user, boolean newUser) {
        var pair = issue(user);
        return new AuthResponse().accessToken(pair.getAccessToken()).refreshToken(pair.getRefreshToken())
                .expiresIn(pair.getExpiresIn()).newUser(newUser).user(accounts.profile(user));
    }

    @Transactional
    public TokenPair refreshToken(RefreshTokenRequest request) {
        if (request == null || request.getRefreshToken() == null || !request.getRefreshToken().matches("[A-Za-z0-9_-]{43}")) {
            throw unauthorized();
        }
        String hash = tokens.hash(request.getRefreshToken());
        UUID user = repository.refreshUser(hash);
        if (user == null || repository.lockPasswordAccount(user) == null) throw unauthorized();
        var session = repository.lockRefresh(hash);
        var now = OffsetDateTime.now(clock);
        if (session == null || session.revokedAt() != null || !session.expiresAt().isAfter(now)) throw unauthorized();
        repository.revoke(session.id(), session.user(), now);
        return issue(session.user());
    }

    @Transactional
    public void logout() {
        UUID user = currentUser.id();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) throw unauthorized();
        try {
            UUID session = UUID.fromString(jwt.getToken().getClaimAsString("sid"));
            repository.revoke(session, user, OffsetDateTime.now(clock));
        } catch (IllegalArgumentException | NullPointerException exception) { throw unauthorized(); }
    }

    private TokenPair issue(UUID user) {
        String refresh = tokens.refresh();
        UUID session = UUID.randomUUID();
        var now = OffsetDateTime.now(clock);
        repository.createRefresh(session, user, tokens.hash(refresh), now, now.plusDays(30));
        return new TokenPair().accessToken(tokens.access(user, session)).refreshToken(refresh).expiresIn(tokens.expiresIn());
    }

    static String email(String value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid email address");
        return normalized;
    }

    private void password(String value) {
        if (value == null || value.length() < 12 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw badRequest("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
    }

    public static class InvalidCredentialsException extends ResponseStatusException {
        public InvalidCredentialsException() { super(HttpStatus.UNAUTHORIZED, "Invalid email or password"); }
    }

    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired session"); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

