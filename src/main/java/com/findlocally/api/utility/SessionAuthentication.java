package com.findlocally.api.utility;

import com.findlocally.api.repository.AuthRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class SessionAuthentication {
    private final AuthRepository sessions;
    private final Clock clock;
    public SessionAuthentication(AuthRepository sessions, Clock clock) { this.sessions = sessions; this.clock = clock; }
    public JwtAuthenticationToken authenticate(Jwt token) {
        UUID user;
        UUID session;
        try {
            user = UUID.fromString(token.getSubject());
            session = UUID.fromString(token.getClaimAsString("sid"));
        } catch (IllegalArgumentException | NullPointerException ex) { throw invalid(); }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(clock.instant())
                || !sessions.activeSession(session, user, OffsetDateTime.now(clock))) throw invalid();
        var roles = sessions.roles(user).stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new JwtAuthenticationToken(token, roles, user.toString());
    }
    private OAuth2AuthenticationException invalid() { return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "Expired or revoked session"); }
}
