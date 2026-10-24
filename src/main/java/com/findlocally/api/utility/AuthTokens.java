package com.findlocally.api.utility;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** JWT access tokens are bound to a persisted, revocable refresh session via sid. */
@Component
public class AuthTokens implements JwtDecoder {
    private static final String ISSUER = "findlocally-api";
    private static final String AUDIENCE = "findlocally-client";
    private final SecureRandom random = new SecureRandom();
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;

    public AuthTokens(Clock clock, @Value("${app.auth.jwt-secret:}") String base64Secret) {
        this.clock = clock;
        byte[] bytes;
        if (base64Secret.isBlank()) {
            bytes = new byte[32];
            random.nextBytes(bytes);
        } else {
            bytes = Base64.getDecoder().decode(base64Secret);
            if (bytes.length < 32) throw new IllegalArgumentException("app.auth.jwt-secret needs at least 32 random bytes, Base64 encoded");
        }
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(ISSUER),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(AUDIENCE)),
                new JwtClaimValidator<Object>("exp", expiry -> expiry != null)));
    }

    public int expiresIn() { return 900; }
    public String access(UUID user, UUID session) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE)).subject(user.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(expiresIn())).id(UUID.randomUUID().toString())
                .claim("sid", session.toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Override
    public Jwt decode(String token) throws JwtException { return decoder.decode(token); }

    public String refresh() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


    public String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
