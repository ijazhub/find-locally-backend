package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class NegotiationRepository {
    private final DbHelper db;
    public NegotiationRepository(DbHelper db) { this.db = db; }
    public void actor(UUID actor) {
        db.getSingleField("SELECT set_config('app.actor_id', :actor, true)",
                new MapSqlParameterSource("actor", actor.toString()), String.class);
    }
    public List<Quotation> quotations(UUID booking) {
        return db.getRows("SELECT * FROM booking_quotations WHERE booking_id=:id ORDER BY version",
                new MapSqlParameterSource("id", booking), (rs, row) -> new Quotation()
                    .id(rs.getObject("id", UUID.class)).version(rs.getInt("version"))
                    .price(new Money(rs.getLong("amount"), rs.getString("currency")))
                    .createdAt(rs.getObject("created_at", OffsetDateTime.class))
                    .expiresAt(rs.getObject("expires_at", OffsetDateTime.class))
                    .state(Quotation.StateEnum.fromValue(rs.getString("state"))));
    }
    public void issue(UUID booking, Money price, OffsetDateTime now, OffsetDateTime deadline) {
        var p = new MapSqlParameterSource("id", booking).addValue("amount", price.getAmount())
                .addValue("currency", price.getCurrency()).addValue("now", now).addValue("deadline", deadline);
        db.update("UPDATE booking_quotations SET state='SUPERSEDED' WHERE booking_id=:id AND state='CURRENT'", p);
        db.insertWithoutKey("""
                INSERT INTO booking_quotations(booking_id,version,amount,currency,state,created_at,expires_at)
                SELECT :id,COALESCE(max(version),0)+1,:amount,:currency,'CURRENT',:now,:deadline
                FROM booking_quotations WHERE booking_id=:id
                """, p);
        db.update("UPDATE bookings SET status='AWAITING_CUSTOMER_APPROVAL',response_deadline=:deadline WHERE id=:id", p);
    }
    public void respond(UUID booking, UUID quotation, boolean accept, String reason) {
        var p = new MapSqlParameterSource("id", booking).addValue("quote", quotation)
                .addValue("state", accept ? "ACCEPTED" : "REJECTED")
                .addValue("status", accept ? "CONFIRMED" : "DECLINED").addValue("reason", reason);
        db.update("UPDATE booking_quotations SET state=:state WHERE id=:quote AND booking_id=:id AND state='CURRENT'", p);
        db.update("""
                UPDATE bookings SET status=:status, decline_reason=:reason,
                  agreed_amount=CASE WHEN :status='CONFIRMED' THEN
                    (SELECT amount FROM booking_quotations WHERE id=:quote AND booking_id=:id) ELSE NULL END
                WHERE id=:id
                """, p);
    }
    public int expire(OffsetDateTime now, UUID business) {
        return db.update("""
                UPDATE bookings SET status='EXPIRED', decline_reason='Response deadline expired' WHERE status IN ('AWAITING_CONFIRMATION','AWAITING_CUSTOMER_APPROVAL')
                 AND response_deadline <= :now
                """ + (business == null ? "" : " AND business_id=:business"),
                new MapSqlParameterSource("now", now).addValue("business", business));
    }
}
