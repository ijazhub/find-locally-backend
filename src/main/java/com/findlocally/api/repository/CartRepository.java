package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CartRepository {
    private final DbHelper db;
    private final ObjectMapper mapper;
    public CartRepository(DbHelper db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }
    public boolean lock(UUID user) {
        return db.getSingleField("SELECT id FROM users WHERE id = :user FOR NO KEY UPDATE",
                new MapSqlParameterSource("user", user), UUID.class) != null;
    }
    public void add(UUID user, CartItemInput input) {
        db.insertWithoutKey("""
                INSERT INTO cart_items (user_id, business_id, service_id, starts_at, fulfilment)
                VALUES (:user, :provider, :service, :starts, :fulfilment)
                """, new MapSqlParameterSource("user", user).addValue("provider", input.getProviderId())
                .addValue("service", input.getServiceId()).addValue("starts", input.getStartsAt())
                .addValue("fulfilment", input.getFulfilment() == null ? null : input.getFulfilment().getValue()));
    }
    public int update(UUID user, UUID item, CartItemInput input) {
        return db.update("""
                UPDATE cart_items SET business_id = :provider, service_id = :service,
                    starts_at = :starts, fulfilment = :fulfilment
                WHERE id = :item AND user_id = :user
                """, new MapSqlParameterSource("user", user).addValue("item", item)
                .addValue("provider", input.getProviderId()).addValue("service", input.getServiceId())
                .addValue("starts", input.getStartsAt())
                .addValue("fulfilment", input.getFulfilment() == null ? null : input.getFulfilment().getValue()));
    }
    public List<CartItemsInner> items(UUID user) {
        return db.getRows("""
                SELECT ci.*, b.business_name, b.owner_name, b.neighbourhood, c.slug AS category,
                    s.name, s.description, s.duration_minutes, s.price_amount, s.currency, s.fulfilment AS offered,
                    (s.active AND s.deleted_at IS NULL AND b.status = 'ACTIVE') AS active
                FROM cart_items ci JOIN businesses b ON b.id = ci.business_id JOIN services s ON s.id = ci.service_id
                LEFT JOIN categories c ON c.id = b.category_id WHERE ci.user_id = :user ORDER BY ci.created_at, ci.id
                """, new MapSqlParameterSource("user", user), (rs, row) -> {
            var array = rs.getArray("offered");
            List<Fulfilment> offered;
            try { offered = Arrays.stream((String[]) array.getArray()).map(Fulfilment::fromValue).toList(); }
            finally { array.free(); }
            var service = new com.findlocally.api.model.Service().id(rs.getObject("service_id", UUID.class)).name(rs.getString("name"))
                    .description(rs.getString("description")).durationMinutes(rs.getInt("duration_minutes"))
                    .price(new Money(rs.getLong("price_amount"), rs.getString("currency"))).fulfilment(offered).active(rs.getBoolean("active"));
            var item = new CartItemsInner().id(rs.getObject("id", UUID.class)).service(service)
                    .provider(new ProviderSummary().id(rs.getObject("business_id", UUID.class)).businessName(rs.getString("business_name"))
                            .ownerName(rs.getString("owner_name")).neighbourhood(rs.getString("neighbourhood")).category(rs.getString("category")))
                    .startsAt(rs.getObject("starts_at", OffsetDateTime.class));
            if (rs.getString("fulfilment") != null) item.setFulfilment(Fulfilment.fromValue(rs.getString("fulfilment")));
            return item;
        });
    }
    public void clear(UUID user) {
        db.update("DELETE FROM cart_items WHERE user_id = :user", new MapSqlParameterSource("user", user));
    }
    public int remove(UUID user, UUID id) {
        return db.update("DELETE FROM cart_items WHERE user_id = :user AND id = :id",
                new MapSqlParameterSource("user", user).addValue("id", id));
    }
    public UUID defaultAddress(UUID user) {
        return db.getSingleField("SELECT id FROM addresses WHERE user_id = :user AND is_default",
                new MapSqlParameterSource("user", user), UUID.class);
    }
    public List<Booking> replay(String body) {
        return mapper.readValue(body, mapper.getTypeFactory().constructCollectionType(List.class, Booking.class));
    }
    public void completeKey(UUID user, UUID key, List<Booking> result) {
        db.update("""
                UPDATE idempotency_keys SET response_status = 201, response_body = CAST(:body AS jsonb)
                WHERE user_id = :user AND key = :key
                """, new MapSqlParameterSource("user", user).addValue("key", key).addValue("body", mapper.writeValueAsString(result)));
    }
}

