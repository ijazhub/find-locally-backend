package com.findlocally.api.repository;

import com.findlocally.api.utility.DbHelper;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessBookingsRepository {
    private final DbHelper db;
    public BusinessBookingsRepository(DbHelper db) { this.db = db; }
    public void transition(UUID business, UUID id, String status, String reason) {
        db.update("UPDATE bookings SET status = :status, decline_reason = :reason WHERE id = :id AND business_id = :business",
                new MapSqlParameterSource("business", business).addValue("id", id).addValue("status", status).addValue("reason", reason));
    }
}

