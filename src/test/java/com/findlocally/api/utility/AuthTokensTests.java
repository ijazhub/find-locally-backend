package com.findlocally.api.utility;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class AuthTokensTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
    private final String secret = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void validatesSignatureExpiryAndSessionClaims() {
        var tokens = new AuthTokens(clock, secret);
        UUID user = UUID.randomUUID(), session = UUID.randomUUID();
        String token = tokens.access(user, session);
        var jwt = tokens.decode(token);
        assertEquals(user.toString(), jwt.getSubject());
        assertEquals(session.toString(), jwt.getClaimAsString("sid"));
        assertThrows(JwtException.class, () -> new AuthTokens(clock, "").decode(token));
        assertThrows(JwtException.class, () -> new AuthTokens(Clock.offset(clock, java.time.Duration.ofMinutes(16)), secret).decode(token));
    }

    @Test
    void createsUnpredictableRefreshTokensAndNeverStoresTheirRawValueAsHash() {
        var tokens = new AuthTokens(clock, secret);
        String refresh = tokens.refresh();
        assertTrue(refresh.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(refresh, tokens.refresh());
        assertEquals(64, tokens.hash(refresh).length());
    }
}
