package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BookingsRepository {
    private final DbHelper dbHelper;
    private final ObjectMapper mapper;
    private final NegotiationRepository negotiation;
    private final com.findlocally.api.utility.CurrentUser current;
    private final java.time.Clock clock;

    public BookingsRepository(DbHelper dbHelper, ObjectMapper mapper, NegotiationRepository negotiation,
            com.findlocally.api.utility.CurrentUser current, java.time.Clock clock) {
        this.dbHelper = dbHelper;
        this.mapper = mapper;
        this.negotiation = negotiation;
        this.current = current;
        this.clock = clock;
    }

    private long responseHours = 24;
    @org.springframework.beans.factory.annotation.Value("${app.bookings.request-response-hours:24}")
    public void configureResponseHours(long hours) {
        if(hours<1 || hours>168) throw new IllegalArgumentException("Request response hours must be between 1 and 168");
        this.responseHours=hours;
    }
    public void actor(UUID user) { negotiation.actor(user); }
    public void releaseExpired(UUID business, OffsetDateTime now) { negotiation.expire(now,business); }
    public record Offer(int durationMinutes, long amount, String currency,
                        List<Fulfilment> fulfilment, boolean byAppointmentOnly) {}
    public record Hours(boolean open, LocalTime from, LocalTime to) {}
    public record IdempotencyRecord(String path, String requestHash, String responseBody) {}

    private static final String BOOKING_SELECT = """
            SELECT bk.*, b.owner_user_id, u.name AS customer_name, b.business_name, b.owner_name,
                b.neighbourhood, b.tags, b.cover_image_url, b.rating_avg, b.review_count,
                c.slug AS category,
                (s.active AND s.deleted_at IS NULL) AS service_active,
                ARRAY(SELECT badge FROM business_badges WHERE business_id = b.id ORDER BY badge) AS badges,
                EXISTS(SELECT 1 FROM reviews WHERE booking_id = bk.id) AS reviewed,
                EXISTS(SELECT 1 FROM saved_businesses WHERE business_id = b.id AND user_id = bk.customer_id) AS saved,
                (SELECT id FROM conversations WHERE business_id = b.id AND customer_id = bk.customer_id) AS conversation_id
            FROM bookings bk
            JOIN businesses b ON b.id = bk.business_id
            JOIN users u ON u.id = bk.customer_id
            JOIN services s ON s.id = bk.service_id
            LEFT JOIN categories c ON c.id = b.category_id
            """;

    public boolean isCustomerAccount(UUID owner) {
        return Boolean.TRUE.equals(dbHelper.getSingleField("SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles r ON r.user_id=u.id AND r.role='CUSTOMER' WHERE u.id = :owner)",
                owner(owner), Boolean.class));
    }

    /** A conflicting insert waits for the first transaction; the row lock then serializes retries. */
    public IdempotencyRecord claimKey(UUID owner, UUID key, String path, String hash) {
        var params = owner(owner).addValue("key", key).addValue("path", path).addValue("hash", hash);
        dbHelper.insertWithoutKey("""
                INSERT INTO idempotency_keys (user_id, key, request_path, request_hash)
                VALUES (:owner, :key, :path, :hash) ON CONFLICT (user_id, key) DO NOTHING
                """, params);
        return dbHelper.getRows("""
                SELECT request_path, request_hash, response_body::text FROM idempotency_keys
                WHERE user_id = :owner AND key = :key FOR UPDATE
                """, params, (rs, row) -> new IdempotencyRecord(rs.getString("request_path"),
                rs.getString("request_hash"), rs.getString("response_body"))).getFirst();
    }

    public Booking replay(String response) {
        return mapper.readValue(response, Booking.class);
    }

    public void completeKey(UUID owner, UUID key, Booking booking) {
        completeKey(owner,key,booking,201);
    }
    public void completeKey(UUID owner, UUID key, Booking booking,int status) {
        dbHelper.update("""
                UPDATE idempotency_keys SET response_status = :status, response_body = CAST(:body AS jsonb)
                WHERE user_id = :owner AND key = :key
                """, owner(owner).addValue("key", key).addValue("status",status).addValue("body", mapper.writeValueAsString(booking)));
    }

    /** Hold the service and business stable until the booking transaction finishes. */
    public Offer findOffer(UUID provider, UUID service) {
        // Business mutations lock the business before its services. Follow the same order
        // to avoid a booking and service edit each waiting for a row held by the other.
        UUID business = dbHelper.getSingleField("SELECT id FROM businesses WHERE id = :provider AND status = 'ACTIVE' FOR SHARE",
                new MapSqlParameterSource("provider", provider), UUID.class);
        if (business == null) return null;
        var rows = dbHelper.getRows("""
                SELECT s.duration_minutes, s.price_amount, s.currency, s.fulfilment, b.by_appointment_only
                FROM services s JOIN businesses b ON b.id = s.business_id
                WHERE s.id = :service AND b.id = :provider
                    AND s.active AND s.deleted_at IS NULL AND b.status = 'ACTIVE'
                FOR SHARE OF s
                """, new MapSqlParameterSource("provider", provider).addValue("service", service),
                (rs, row) -> new Offer(rs.getInt("duration_minutes"), rs.getLong("price_amount"),
                        rs.getString("currency"), strings(rs, "fulfilment").stream().map(Fulfilment::fromValue).toList(),
                        rs.getBoolean("by_appointment_only")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public String serviceName(UUID service) {
        return dbHelper.getSingleField("SELECT name FROM services WHERE id = :id",
                new MapSqlParameterSource("id", service), String.class);
    }

    public Address findAddress(UUID owner, UUID addressId) {
        var rows = dbHelper.getRows("""
                SELECT * FROM addresses WHERE id = :address AND user_id = :owner FOR SHARE
                """, owner(owner).addValue("address", addressId), (rs, row) -> {
            var address = new Address().id(addressId).label(rs.getString("label"))
                    .line1(rs.getString("line1")).line2(rs.getString("line2"))
                    .neighbourhood(rs.getString("neighbourhood")).city(rs.getString("city"))
                    .state(rs.getString("state")).pincode(rs.getString("pincode"))
                    .isDefault(rs.getBoolean("is_default"));
            Double lat = rs.getObject("lat", Double.class);
            Double lng = rs.getObject("lng", Double.class);
            if (lat != null && lng != null) address.setLocation(new GeoPoint().lat(lat).lng(lng));
            return address;
        });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean blocked(UUID provider, LocalDate first, LocalDate last) {
        return Boolean.TRUE.equals(dbHelper.getSingleField("""
                SELECT EXISTS(SELECT 1 FROM business_blocked_dates
                    WHERE business_id = :provider AND blocked_on BETWEEN :first AND :last)
                """, new MapSqlParameterSource("provider", provider).addValue("first", first)
                .addValue("last", last), Boolean.class));
    }

    public Hours hours(UUID provider, String day) {
        var rows = dbHelper.getRows("""
                SELECT is_open, open_time, close_time FROM business_hours WHERE business_id = :provider AND day = :day
                """, new MapSqlParameterSource("provider", provider).addValue("day", day),
                (rs, row) -> new Hours(rs.getBoolean("is_open"), rs.getObject("open_time", LocalTime.class),
                        rs.getObject("close_time", LocalTime.class)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public UUID insert(UUID owner, BookingCreate input, Offer offer, Address address, OffsetDateTime endsAt) {
        return dbHelper.insertWithKey("""
                INSERT INTO bookings (customer_id, business_id, service_id, starts_at, ends_at, fulfilment,
                    address_id, address_snapshot, note, service_name, duration_minutes, price_amount, currency, proposed_amount, hold_expires_at, response_deadline)
                VALUES (:owner, :provider, :service, :start, :end, :fulfilment, :address, CAST(:snapshot AS jsonb),
                    :note, :name, :duration, :amount, :currency, :proposed,
                    least(:start, :now + make_interval(hours => :hours)), least(:start, :now + make_interval(hours => :hours)))
                """, owner(owner).addValue("provider", input.getProviderId()).addValue("service", input.getServiceId())
                .addValue("start", input.getStartsAt()).addValue("end", endsAt)
                .addValue("fulfilment", input.getFulfilment().getValue())
                .addValue("address", address == null ? null : address.getId())
                .addValue("snapshot", address == null ? null : mapper.writeValueAsString(address))
                .addValue("note", input.getNote()).addValue("name", serviceName(input.getServiceId()))
                .addValue("duration", offer.durationMinutes()).addValue("amount", offer.amount())
                .addValue("currency", offer.currency()).addValue("hours", Math.toIntExact(responseHours)).addValue("now",OffsetDateTime.now(clock))
                .addValue("proposed", input.getProposedPrice()==null ? offer.amount() : input.getProposedPrice().getAmount()), "id", UUID.class);
    }

    public Booking find(UUID owner, UUID bookingId, boolean lock) {
        var rows = dbHelper.getRows(BOOKING_SELECT + """
                 WHERE bk.customer_id = :owner AND bk.id = :id
                """ + (lock ? " FOR UPDATE OF bk" : ""), owner(owner).addValue("id", bookingId), this::mapBooking);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Booking findParticipant(UUID user,UUID id) {
        var rows=dbHelper.getRows(BOOKING_SELECT+" WHERE bk.id=:id AND (bk.customer_id=:owner OR b.owner_user_id=:owner)",
            owner(user).addValue("id",id),this::mapBooking);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Booking findBusiness(UUID business, UUID id, boolean lock) {
        var rows = dbHelper.getRows(BOOKING_SELECT + " WHERE bk.business_id = :business AND bk.id = :id"
                + (lock ? " FOR UPDATE OF bk" : ""),
                new MapSqlParameterSource("business", business).addValue("id", id), this::mapBooking);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public BookingPage businessPage(UUID business, BookingStatus status, OffsetDateTime from, OffsetDateTime until,
            com.findlocally.api.utility.Pages page) {
        String filter = " WHERE bk.business_id = :business"
                + (status == null ? "" : " AND bk.status = :status")
                + (status==BookingStatus.AWAITING_CONFIRMATION || status==BookingStatus.AWAITING_CUSTOMER_APPROVAL ? " AND bk.response_deadline>:now" : "")
                + (from == null ? "" : " AND bk.starts_at >= :from")
                + (until == null ? "" : " AND bk.starts_at < :until");
        var params = new MapSqlParameterSource("business", business).addValue("status", status == null ? null : status.getValue())
                .addValue("from", from).addValue("until", until).addValue("now",OffsetDateTime.now(clock));
        long count = dbHelper.getSingleField("SELECT count(*) FROM bookings bk" + filter, params, Long.class);
        return new BookingPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(dbHelper.getRows(BOOKING_SELECT + filter + " ORDER BY bk.starts_at, bk.id LIMIT :size OFFSET :offset",
                        params.addValue("size", page.size()).addValue("offset", page.offset()), this::mapBooking));
    }

    public List<Booking> businessSchedule(UUID business, OffsetDateTime from, OffsetDateTime until) {
        return dbHelper.getRows(BOOKING_SELECT + """
                 WHERE bk.business_id = :business AND bk.starts_at < :until AND bk.ends_at > :from
                 AND bk.status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED') ORDER BY bk.starts_at, bk.id
                """, new MapSqlParameterSource("business", business).addValue("from", from).addValue("until", until), this::mapBooking);
    }

    public void cancel(UUID owner, UUID bookingId, String reason) {
        dbHelper.update("""
                UPDATE bookings SET status = 'CANCELLED', cancel_reason = :reason
                WHERE id = :id AND customer_id = :owner AND status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED')
                """, owner(owner).addValue("id", bookingId).addValue("reason", reason));
    }

    public List<Booking> list(UUID owner, String tab, int page, int size) {
        return dbHelper.getRows(BOOKING_SELECT + " WHERE bk.customer_id = :owner AND " + tabFilter(tab)
                + (tab.equals("UPCOMING") ? " ORDER BY bk.starts_at, bk.id" : " ORDER BY bk.starts_at DESC, bk.id")
                + " LIMIT :size OFFSET :offset",
                owner(owner).addValue("size", size).addValue("offset", (long) page * size), this::mapBooking);
    }

    public long count(UUID owner, String tab) {
        return dbHelper.getSingleField("SELECT count(*) FROM bookings bk WHERE bk.customer_id = :owner AND " + tabFilter(tab),
                owner(owner), Long.class);
    }

    public BookingSummary summary(UUID owner, OffsetDateTime now) {
        return dbHelper.getRows("""
                SELECT count(*) FILTER (WHERE status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED'))::integer AS upcoming,
                    count(*) FILTER (WHERE status = 'COMPLETED')::integer AS done,
                    count(*) FILTER (WHERE status IN ('CANCELLED', 'DECLINED', 'EXPIRED'))::integer AS cancelled,
                    min(starts_at) FILTER (WHERE status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED') AND starts_at >= :now) AS next,
                    (SELECT count(*)::integer FROM cart_items WHERE user_id = :owner) AS cart
                FROM bookings WHERE customer_id = :owner
                """, owner(owner).addValue("now", now), (rs, row) -> new BookingSummary()
                .upcomingCount(rs.getInt("upcoming")).doneCount(rs.getInt("done"))
                .cancelledCount(rs.getInt("cancelled")).cartCount(rs.getInt("cart"))
                .nextBookingAt(rs.getObject("next", OffsetDateTime.class))).getFirst();
    }

    private String tabFilter(String tab) {
        return switch (tab) {
            case "UPCOMING" -> "bk.status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED')";
            case "DONE" -> "bk.status = 'COMPLETED'";
            case "CANCELLED" -> "bk.status IN ('CANCELLED', 'DECLINED', 'EXPIRED')";
            default -> throw new IllegalArgumentException("Unknown booking tab");
        };
    }

    private Booking mapBooking(ResultSet rs, int row) throws SQLException {
        var price = new Money(rs.getLong("price_amount"), rs.getString("currency"));
        var provider = new ProviderSummary().id(rs.getObject("business_id", UUID.class))
                .businessName(rs.getString("business_name")).ownerName(rs.getString("owner_name"))
                .category(rs.getString("category")).neighbourhood(rs.getString("neighbourhood"))
                .tags(strings(rs, "tags")).badges(strings(rs, "badges").stream().map(Badge::fromValue).toList())
                .rating(rs.getFloat("rating_avg")).reviewCount(rs.getInt("review_count")).saved(rs.getBoolean("saved"));
        String cover = rs.getString("cover_image_url");
        if (cover != null) provider.setCoverImageUrl(URI.create(cover));
        var fulfilment = Fulfilment.fromValue(rs.getString("fulfilment"));
        // Historical name, duration and money come from the booking, even after a service edit or deletion.
        var service = new com.findlocally.api.model.Service().id(rs.getObject("service_id", UUID.class))
                .name(rs.getString("service_name")).durationMinutes(rs.getInt("duration_minutes"))
                .price(price).fulfilment(List.of(fulfilment)).active(rs.getBoolean("service_active"));
        var booking = new Booking().id(rs.getObject("id", UUID.class))
                .status(BookingStatus.fromValue(rs.getString("status"))).provider(provider).service(service)
                .customer(new BookingCustomer().id(rs.getObject("customer_id", UUID.class)).name(rs.getString("customer_name")))
                .startsAt(rs.getObject("starts_at", OffsetDateTime.class)).endsAt(rs.getObject("ends_at", OffsetDateTime.class))
                .fulfilment(fulfilment).note(rs.getString("note")).total(price).reviewed(rs.getBoolean("reviewed"))
                .conversationId(rs.getObject("conversation_id", UUID.class)).createdAt(rs.getObject("created_at", OffsetDateTime.class));
        String snapshot = rs.getString("address_snapshot");
        if (snapshot != null) booking.setAddress(mapper.readValue(snapshot, Address.class));
        booking.setListedPrice(price);
        booking.setProposedPrice(new Money(rs.getLong("proposed_amount"),rs.getString("currency")));
        Long agreed=rs.getObject("agreed_amount",Long.class);
        if (agreed!=null) booking.setAgreedPrice(new Money(agreed,rs.getString("currency")));
        booking.setHoldExpiresAt(rs.getObject("hold_expires_at",OffsetDateTime.class));
        booking.setResponseDeadline(rs.getObject("response_deadline",OffsetDateTime.class));
        booking.setDeclineReason(rs.getString("decline_reason"));
        booking.setQuotations(negotiation.quotations(booking.getId()));
        booking.setAllowedActions(com.findlocally.api.utility.BookingActions.allowed(booking,
                current.optionalId(), rs.getObject("owner_user_id",UUID.class), clock.instant()));
        return booking;
    }

    private List<String> strings(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        if (array == null) return List.of();
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private MapSqlParameterSource owner(UUID owner) {
        return new MapSqlParameterSource("owner", owner);
    }
}

