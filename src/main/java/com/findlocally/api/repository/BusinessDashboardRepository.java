package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessDashboardRepository {
    private final DbHelper db;
    private final Clock clock;
    public BusinessDashboardRepository(DbHelper db,Clock clock) { this.db = db; this.clock=clock; }
    public BusinessProfile profile(UUID id) {
        return db.getRows("""
                SELECT b.*, c.slug AS category FROM businesses b LEFT JOIN categories c ON c.id = b.category_id WHERE b.id = :id
                """, params(id), (rs, row) -> {
            var result = new BusinessProfile().id(id).ownerName(rs.getString("owner_name")).businessName(rs.getString("business_name"))
                    .category(rs.getString("category")).bio(rs.getString("bio")).note(rs.getString("note")).city(rs.getString("city"))
                    .neighbourhood(rs.getString("neighbourhood")).verificationStatus(BusinessProfile.VerificationStatusEnum.fromValue(rs.getString("verification_status")));
            String mode = rs.getString("work_mode"), avatar = rs.getString("avatar_url"), cover = rs.getString("cover_image_url");
            if (mode != null) result.setWorkMode(BusinessProfile.WorkModeEnum.fromValue(mode));
            if (avatar != null) result.setAvatarUrl(URI.create(avatar));
            if (cover != null) result.setCoverImageUrl(URI.create(cover));
            return result;
        }).getFirst();
    }
    public void update(UUID id, BusinessProfileUpdate input, String avatar, String cover) {
        db.update("""
                UPDATE businesses SET business_name = COALESCE(:name, business_name), bio = COALESCE(:bio, bio),
                    note = COALESCE(:note, note), neighbourhood = COALESCE(:area, neighbourhood), work_mode = COALESCE(:mode, work_mode),
                    avatar_url = COALESCE(:avatar, avatar_url), cover_image_url = COALESCE(:cover, cover_image_url) WHERE id = :id
                """, params(id).addValue("name", input.getBusinessName()).addValue("bio", input.getBio()).addValue("note", input.getNote())
                .addValue("area", input.getNeighbourhood()).addValue("mode", input.getWorkMode() == null ? null : input.getWorkMode().getValue())
                .addValue("avatar", avatar).addValue("cover", cover));
    }
    public AvailabilityInput availability(UUID id) {
        Boolean appointment = db.getSingleField("SELECT by_appointment_only FROM businesses WHERE id = :id", params(id), Boolean.class);
        var weekly = db.getRows("SELECT * FROM business_hours WHERE business_id = :id ORDER BY array_position(ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN'], day::text)",
                params(id), (rs, row) -> {
            var day = new AvailabilityInputWeeklyInner().day(AvailabilityInputWeeklyInner.DayEnum.fromValue(rs.getString("day"))).open(rs.getBoolean("is_open"));
            LocalTime from = rs.getObject("open_time", LocalTime.class), to = rs.getObject("close_time", LocalTime.class);
            if (from != null) day.setFrom(from.toString());
            if (to != null) day.setTo(to.toString());
            return day;
        });
        var blocked = db.getRows("SELECT blocked_on FROM business_blocked_dates WHERE business_id = :id ORDER BY blocked_on", params(id),
                (rs, row) -> rs.getObject("blocked_on", LocalDate.class));
        return new AvailabilityInput().byAppointmentOnly(appointment).weekly(weekly).blockedDates(blocked);
    }
    public DashboardOverview overview(UUID id, OffsetDateTime from, OffsetDateTime until) {
        return db.getRows("""
                SELECT b.*, u.name AS user_name, cardinality(b.completed_steps) AS completed_steps_count,
                  (SELECT count(*)::integer FROM bookings WHERE business_id = b.id AND ends_at > :from AND starts_at < :until
                   AND status = 'CONFIRMED') AS today,
                  (SELECT count(*)::integer FROM bookings WHERE business_id = b.id AND status = 'AWAITING_CONFIRMATION' AND response_deadline>:now) AS awaiting,
                  (SELECT count(*)::integer FROM bookings WHERE business_id=b.id AND status='AWAITING_CUSTOMER_APPROVAL' AND response_deadline>:now) AS quotations,
                  (SELECT count(*)::integer FROM bookings WHERE business_id=b.id AND status IN ('AWAITING_CONFIRMATION','AWAITING_CUSTOMER_APPROVAL') AND response_deadline>:now) AS holds,
                  (SELECT count(DISTINCT c.id)::integer FROM conversations c JOIN messages m ON m.conversation_id=c.id
                    WHERE c.business_id=b.id AND m.sender_id<>b.owner_user_id AND m.read_at IS NULL) AS unread
                FROM businesses b JOIN users u ON u.id = b.owner_user_id WHERE b.id = :id
                """, params(id).addValue("from", from).addValue("until", until).addValue("now",OffsetDateTime.now(clock)), (rs, row) -> new DashboardOverview()
                .greetingName(rs.getString("user_name")).today(rs.getInt("today")).awaitingReply(rs.getInt("awaiting"))
                .awaitingCustomerApproval(rs.getInt("quotations")).pendingHolds(rs.getInt("holds")).unreadConversations(rs.getInt("unread"))
                .rating(rs.getBigDecimal("rating_avg")).profileCompletion(new DashboardOverviewProfileCompletion()
                        .completedSteps(rs.getInt("completed_steps_count")).totalSteps(6).nextStep(rs.getString("current_step")))).getFirst();
    }
    public Earnings earnings(UUID id, YearMonth month, ZoneId zone) {
        var params = params(id).addValue("from", month.atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .addValue("until", month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime()).addValue("zone", zone.getId());
        String where = " WHERE business_id = :id AND status = 'COMPLETED' AND currency = 'INR' AND starts_at >= :from AND starts_at < :until";
        var result = db.getRows("SELECT COALESCE(sum(agreed_amount),0) AS total, count(*)::integer AS completed FROM bookings" + where,
                params, (rs, row) -> new Earnings().month(month.toString()).total(new Money(rs.getLong("total"), "INR")).completedBookings(rs.getInt("completed"))).getFirst();
        result.setDaily(db.getRows("""
                SELECT (starts_at AT TIME ZONE :zone)::date AS day, sum(agreed_amount) AS amount FROM bookings
                """ + where + " GROUP BY day ORDER BY day", params,
                (rs, row) -> new EarningsDailyInner().date(rs.getObject("day", LocalDate.class)).amount(new Money(rs.getLong("amount"), "INR"))));
        result.setTotals(db.getRows("""
                SELECT currency,sum(agreed_amount) AS amount FROM bookings
                WHERE business_id=:id AND status='COMPLETED' AND starts_at>=:from AND starts_at<:until
                GROUP BY currency ORDER BY currency
                """,params,(rs,row) -> new Money(rs.getLong("amount"),rs.getString("currency"))));
        result.setCompletedBookings(db.getSingleField("SELECT count(*)::integer FROM bookings WHERE business_id=:id AND status='COMPLETED' AND starts_at>=:from AND starts_at<:until",params,Integer.class));
        return result;
    }
    private MapSqlParameterSource params(UUID id) { return new MapSqlParameterSource("id", id); }
}

