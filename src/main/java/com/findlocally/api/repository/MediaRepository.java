package com.findlocally.api.repository;

import com.findlocally.api.utility.DbHelper;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRepository {
    private final DbHelper db;
    public MediaRepository(DbHelper db) { this.db = db; }
    public record Upload(UUID id, String purpose, String key, String contentType, long size, String status) {}
    public void create(UUID id, UUID user, String purpose, String key, String type, long size) {
        db.insertWithoutKey("""
                INSERT INTO uploads (id, user_id, purpose, object_key, content_type, size_bytes)
                VALUES (:id, :user, :purpose, :key, :type, :size)
                """, new MapSqlParameterSource("id", id).addValue("user", user).addValue("purpose", purpose)
                .addValue("key", key).addValue("type", type).addValue("size", size));
    }
    public Upload lock(UUID user, UUID id) {
        var rows = db.getRows("SELECT * FROM uploads WHERE user_id = :user AND id = :id FOR UPDATE",
                new MapSqlParameterSource("user", user).addValue("id", id), (rs, row) -> new Upload(id,
                        rs.getString("purpose"), rs.getString("object_key"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("status")));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void complete(UUID id) {
        db.update("UPDATE uploads SET status = 'UPLOADED' WHERE id = :id", new MapSqlParameterSource("id", id));
    }
}

