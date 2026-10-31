package com.findlocally.api.repository;

import com.findlocally.api.model.Fulfilment;
import com.findlocally.api.model.Money;
import com.findlocally.api.model.Service;
import com.findlocally.api.model.ServiceInput;
import com.findlocally.api.utility.DbHelper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessServicesRepository {
    private final DbHelper dbHelper;

    public BusinessServicesRepository(DbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public UUID findBusinessId(UUID ownerId) {
        return dbHelper.getSingleField("SELECT id FROM businesses WHERE owner_user_id = :ownerId AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id=:ownerId AND r.role='BUSINESS')",
                new MapSqlParameterSource("ownerId", ownerId), UUID.class);
    }

    public UUID lockBusiness(UUID ownerId) {
        return dbHelper.getSingleField("SELECT id FROM businesses WHERE owner_user_id = :ownerId AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id=:ownerId AND r.role='BUSINESS') FOR UPDATE",
                new MapSqlParameterSource("ownerId", ownerId), UUID.class);
    }

    public List<Service> findAll(UUID businessId) {
        return dbHelper.getRows("""
                SELECT * FROM services WHERE business_id = :businessId AND deleted_at IS NULL
                ORDER BY sort_order, created_at, id
                """, new MapSqlParameterSource("businessId", businessId), this::mapService);
    }

    public Service create(UUID businessId, ServiceInput input) {
        return dbHelper.getRows("""
                INSERT INTO services (business_id, name, description, duration_minutes, price_amount, currency, fulfilment)
                VALUES (:businessId, :name, :description, :duration, :amount, :currency, CAST(:fulfilment AS text[]))
                RETURNING *
                """, parameters(businessId, input), this::mapService).getFirst();
    }

    public Service update(UUID businessId, UUID serviceId, ServiceInput input) {
        return first(dbHelper.getRows("""
                UPDATE services SET name = :name, description = :description, duration_minutes = :duration,
                    price_amount = :amount, currency = :currency, fulfilment = CAST(:fulfilment AS text[])
                WHERE id = :serviceId AND business_id = :businessId AND deleted_at IS NULL RETURNING *
                """, parameters(businessId, input).addValue("serviceId", serviceId), this::mapService));
    }

    public Service setActive(UUID businessId, UUID serviceId, boolean active) {
        return first(dbHelper.getRows("""
                UPDATE services SET active = :active
                WHERE id = :serviceId AND business_id = :businessId AND deleted_at IS NULL RETURNING *
                """, ids(businessId, serviceId).addValue("active", active), this::mapService));
    }

    public int delete(UUID businessId, UUID serviceId) {
        return dbHelper.update("""
                UPDATE services SET deleted_at = now(), active = false
                WHERE id = :serviceId AND business_id = :businessId AND deleted_at IS NULL
                """, ids(businessId, serviceId));
    }

    public void deleteAll(UUID businessId) {
        dbHelper.update("""
                UPDATE services SET deleted_at = now(), active = false
                WHERE business_id = :businessId AND deleted_at IS NULL
                """, new MapSqlParameterSource("businessId", businessId));
    }

    private MapSqlParameterSource ids(UUID businessId, UUID serviceId) {
        return new MapSqlParameterSource("businessId", businessId).addValue("serviceId", serviceId);
    }

    private MapSqlParameterSource parameters(UUID businessId, ServiceInput input) {
        var fulfilment = input.getFulfilment();
        String array = fulfilment == null || fulfilment.isEmpty() ? "{AT_STUDIO}"
                : "{" + String.join(",", fulfilment.stream().map(Fulfilment::getValue).toList()) + "}";
        return new MapSqlParameterSource("businessId", businessId).addValue("name", input.getName())
                .addValue("description", input.getDescription()).addValue("duration", input.getDurationMinutes())
                .addValue("amount", input.getPrice().getAmount()).addValue("currency", input.getPrice().getCurrency())
                .addValue("fulfilment", array);
    }

    private Service first(List<Service> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Service mapService(ResultSet rs, int row) throws SQLException {
        var service = new Service();
        service.setId(rs.getObject("id", UUID.class));
        service.setName(rs.getString("name"));
        service.setDescription(rs.getString("description"));
        service.setDurationMinutes(rs.getInt("duration_minutes"));
        service.setPrice(new Money().amount(rs.getLong("price_amount")).currency(rs.getString("currency")));
        var array = rs.getArray("fulfilment");
        try {
            service.setFulfilment(Arrays.stream((String[]) array.getArray()).map(Fulfilment::fromValue).toList());
        } finally {
            array.free();
        }
        service.setActive(rs.getBoolean("active"));
        return service;
    }
}

