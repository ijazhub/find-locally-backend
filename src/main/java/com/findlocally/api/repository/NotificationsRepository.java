package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationsRepository {
    private final DbHelper db;
    public NotificationsRepository(DbHelper db) { this.db = db; }
    public void notify(UUID user, String type, String title, String body, String link) {
        db.insertWithoutKey("""
                INSERT INTO notifications (user_id, type, title, body, deep_link) VALUES (:user, :type, :title, :body, :link)
                """, new MapSqlParameterSource("user", user).addValue("type", type).addValue("title", title)
                .addValue("body", body).addValue("link", link));
    }
    public UUID businessOwner(UUID business) {
        return db.getSingleField("SELECT owner_user_id FROM businesses WHERE id = :id",
                new MapSqlParameterSource("id", business), UUID.class);
    }
    public NotificationPage page(UUID user, boolean unread, Pages page) {
        String where = " WHERE user_id = :user" + (unread ? " AND read_at IS NULL" : "");
        var params = new MapSqlParameterSource("user", user);
        long count = db.getSingleField("SELECT count(*) FROM notifications" + where, params, Long.class);
        return new NotificationPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(db.getRows("SELECT * FROM notifications" + where + " ORDER BY created_at DESC, id LIMIT :size OFFSET :offset",
                        params.addValue("size", page.size()).addValue("offset", page.offset()), (rs, row) -> new Notification()
                        .id(rs.getObject("id", UUID.class)).type(Notification.TypeEnum.fromValue(rs.getString("type")))
                        .title(rs.getString("title")).body(rs.getString("body")).deepLink(rs.getString("deep_link"))
                        .read(rs.getObject("read_at") != null).createdAt(rs.getObject("created_at", OffsetDateTime.class))));
    }
    public int read(UUID user, UUID id) {
        return db.update("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :user",
                new MapSqlParameterSource("id", id).addValue("user", user));
    }
    public void readAll(UUID user) {
        db.update("UPDATE notifications SET read_at = now() WHERE user_id = :user AND read_at IS NULL",
                new MapSqlParameterSource("user", user));
    }
}

