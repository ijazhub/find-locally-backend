package com.findlocally.api.repository;

import com.findlocally.api.utility.DbHelper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    private final DbHelper dbHelper;
    public AuthRepository(DbHelper dbHelper) { this.dbHelper = dbHelper; }

    public record Refresh(UUID id, UUID user, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}
    public record Credential(UUID id, String hash, OffsetDateTime lockedUntil) {}
    public record PasswordAccount(UUID id, String email, String hash) {}
    public record PasswordReset(UUID user, String email, OffsetDateTime expiresAt, OffsetDateTime consumedAt) {}

    public PasswordAccount lockPasswordAccount(UUID user) {
        var rows = dbHelper.getRows("SELECT id, lower(btrim(email)) AS email, password_hash FROM users WHERE id = :user FOR UPDATE",
                new MapSqlParameterSource("user", user), (rs, row) -> new PasswordAccount(rs.getObject("id", UUID.class),
                        rs.getString("email"), rs.getString("password_hash")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean resetThrottled(UUID user, OffsetDateTime now) {
        return Boolean.TRUE.equals(dbHelper.getSingleField("""
                SELECT count(*) >= 5 OR COALESCE(max(created_at) > :recent, false)
                FROM password_reset_tokens WHERE user_id = :user AND created_at > :since
                """, new MapSqlParameterSource("user", user).addValue("recent", now.minusSeconds(60))
                .addValue("since", now.minusHours(1)), Boolean.class));
    }

    public void createReset(UUID user, String email, String hash, OffsetDateTime now, OffsetDateTime expiry) {
        dbHelper.insertWithoutKey("""
                INSERT INTO password_reset_tokens (user_id, email, token_hash, expires_at, created_at)
                VALUES (:user, :email, :hash, :expiry, :now)
                """, new MapSqlParameterSource("user", user).addValue("email", email).addValue("hash", hash)
                .addValue("expiry", expiry).addValue("now", now));
    }

    public PasswordReset findReset(String hash) {
        var rows = dbHelper.getRows("SELECT * FROM password_reset_tokens WHERE token_hash = :hash",
                new MapSqlParameterSource("hash", hash), (rs, row) -> new PasswordReset(rs.getObject("user_id", UUID.class),
                        rs.getString("email"), rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("consumed_at", OffsetDateTime.class)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void invalidateResets(UUID user, OffsetDateTime now) {
        dbHelper.update("UPDATE password_reset_tokens SET consumed_at = :now WHERE user_id = :user AND consumed_at IS NULL",
                new MapSqlParameterSource("user", user).addValue("now", now));
    }

    public void changePassword(UUID user, String hash) {
        dbHelper.update("UPDATE users SET password_hash = :hash, failed_login_attempts = 0, locked_until = NULL WHERE id = :user",
                new MapSqlParameterSource("user", user).addValue("hash", hash));
    }

    public void revokeAll(UUID user, OffsetDateTime now) {
        dbHelper.update("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :user AND revoked_at IS NULL",
                new MapSqlParameterSource("user", user).addValue("now", now));
    }

    public UUID refreshUser(String hash) {
        return dbHelper.getSingleField("SELECT user_id FROM refresh_tokens WHERE token_hash = :hash",
                new MapSqlParameterSource("hash", hash), UUID.class);
    }

    public UUID register(String email, String hash, String name) {
        return register(email, hash, name, "CUSTOMER");
    }

    public UUID register(String email, String hash, String name, String accountType) {
        var rows = dbHelper.getRows("""
                INSERT INTO users (email, password_hash, name) VALUES (:email, :hash, :name)
                ON CONFLICT DO NOTHING RETURNING id
                """, new MapSqlParameterSource("email", email).addValue("hash", hash).addValue("name", name),
                (rs, row) -> rs.getObject("id", UUID.class));
        if (rows.isEmpty()) return null;
        UUID user = rows.getFirst();
        dbHelper.insertWithoutKey("INSERT INTO user_roles (user_id, role) VALUES (:id, :role)", new MapSqlParameterSource("id", user).addValue("role", accountType));
        return user;
    }

    public Credential lockCredential(String email) {
        var rows = dbHelper.getRows("SELECT id, password_hash, locked_until FROM users WHERE lower(btrim(email)) = :email FOR UPDATE",
                new MapSqlParameterSource("email", email), (rs, row) -> new Credential(rs.getObject("id", UUID.class),
                        rs.getString("password_hash"), rs.getObject("locked_until", OffsetDateTime.class)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void failedLogin(UUID user, OffsetDateTime now) {
        dbHelper.update("""
                UPDATE users SET failed_login_attempts = CASE WHEN locked_until <= :now THEN 1 ELSE failed_login_attempts + 1 END,
                    locked_until = CASE WHEN locked_until <= :now THEN NULL
                        WHEN failed_login_attempts + 1 >= 5 THEN :until ELSE locked_until END WHERE id = :id
                """, new MapSqlParameterSource("id", user).addValue("now", now).addValue("until", now.plusMinutes(15)));
    }

    public void clearFailures(UUID user) {
        dbHelper.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = :id", new MapSqlParameterSource("id", user));
    }

    public void createRefresh(UUID id, UUID user, String hash, OffsetDateTime now, OffsetDateTime expiry) {
        dbHelper.insertWithoutKey("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (:id, :user, :hash, :expiry, :now)
                """, new MapSqlParameterSource("id", id).addValue("user", user).addValue("hash", hash)
                .addValue("expiry", expiry).addValue("now", now));
    }

    public Refresh lockRefresh(String hash) {
        var rows = dbHelper.getRows("SELECT * FROM refresh_tokens WHERE token_hash = :hash FOR UPDATE",
                new MapSqlParameterSource("hash", hash), (rs, row) -> new Refresh(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void revoke(UUID id, UUID user, OffsetDateTime now) {
        dbHelper.update("UPDATE refresh_tokens SET revoked_at = :now WHERE id = :id AND user_id = :user AND revoked_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("user", user).addValue("now", now));
    }

    public boolean activeSession(UUID session, UUID user, OffsetDateTime now) {
        return Boolean.TRUE.equals(dbHelper.getSingleField("""
                SELECT EXISTS(SELECT 1 FROM refresh_tokens
                    WHERE id = :id AND user_id = :user AND revoked_at IS NULL AND expires_at > :now)
                """, new MapSqlParameterSource("id", session).addValue("user", user).addValue("now", now), Boolean.class));
    }

    public List<String> roles(UUID user) {
        return dbHelper.getRows("SELECT role FROM user_roles WHERE user_id = :user",
                new MapSqlParameterSource("user", user), (rs, row) -> rs.getString("role"));
    }
}

