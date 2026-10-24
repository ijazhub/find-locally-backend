package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewsRepository {
    private final DbHelper db;
    public ReviewsRepository(DbHelper db) { this.db = db; }
    private static final String SELECT = """
            SELECT r.*, u.name AS author_name, bk.service_name FROM reviews r
            JOIN users u ON u.id = r.author_id JOIN bookings bk ON bk.id = r.booking_id
            """;
    public Review find(UUID id) {
        return db.getRows(SELECT + " WHERE r.id = :id", new MapSqlParameterSource("id", id), this::map).getFirst();
    }
    public boolean exists(UUID booking) {
        return Boolean.TRUE.equals(db.getSingleField("SELECT EXISTS(SELECT 1 FROM reviews WHERE booking_id = :id)",
                new MapSqlParameterSource("id", booking), Boolean.class));
    }
    public Review create(UUID customer, UUID booking, UUID provider, ReviewInput input) {
        UUID id = db.insertWithKey("""
                INSERT INTO reviews (booking_id, business_id, author_id, rating, text)
                VALUES (:booking, :provider, :customer, :rating, :text)
                """, new MapSqlParameterSource("booking", booking).addValue("provider", provider).addValue("customer", customer)
                .addValue("rating", input.getRating()).addValue("text", input.getText()), "id", UUID.class);
        return find(id);
    }
    public ReviewPage page(UUID provider, Pages page) {
        var p = new MapSqlParameterSource("provider", provider);
        long count = db.getSingleField("SELECT count(*) FROM reviews WHERE business_id = :provider", p, Long.class);
        return new ReviewPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(db.getRows(SELECT + " WHERE r.business_id = :provider ORDER BY r.created_at DESC, r.id LIMIT :size OFFSET :offset",
                        p.addValue("size", page.size()).addValue("offset", page.offset()), this::map));
    }
    public Review reply(UUID business, UUID review, String text) {
        var ids = db.getRows("""
                UPDATE reviews SET provider_reply = :text, replied_at = now()
                WHERE id = :id AND business_id = :business RETURNING id
                """, new MapSqlParameterSource("text", text).addValue("id", review).addValue("business", business),
                (rs, row) -> rs.getObject("id", UUID.class));
        return ids.isEmpty() ? null : find(review);
    }
    private Review map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Review().id(rs.getObject("id", UUID.class)).authorName(rs.getString("author_name"))
                .rating(rs.getInt("rating")).text(rs.getString("text")).serviceName(rs.getString("service_name"))
                .createdAt(rs.getObject("created_at", OffsetDateTime.class)).providerReply(rs.getString("provider_reply"));
    }
}

