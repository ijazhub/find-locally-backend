package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final DbHelper dbHelper;
    private final Clock clock;
    public CatalogRepository(DbHelper dbHelper,Clock clock) { this.dbHelper = dbHelper; this.clock=clock; }

    public record Search(String category, String q, String city, Double lat, Double lng, BigDecimal maxDistance,
                         BigDecimal minRating, Long maxPrice, boolean verified, String sort, int page, int size, UUID viewer) {}
    public record ProviderRow(ProviderSummary summary, String bio, String avatar, String workMode, boolean followed) {}
    public record Offer(int duration, boolean appointmentOnly) {}
    public record Hours(String day, boolean open, LocalTime from, LocalTime to) {}
    public record Busy(Instant start, Instant end) {}

    private String projection(boolean coordinates) {
        String distance = coordinates ? """
                CASE WHEN b.lat IS NOT NULL AND b.lng IS NOT NULL THEN 6371.0 * acos(least(1.0, greatest(-1.0,
                    sin(radians(b.lat)) * sin(radians(CAST(:lat AS double precision)))
                    + cos(radians(b.lat)) * cos(radians(CAST(:lat AS double precision)))
                    * cos(radians(b.lng - CAST(:lng AS double precision)))))) END
                """ : "NULL::double precision";
        return """
                SELECT b.*, c.slug AS category,
                    (SELECT min(price_amount) FROM services WHERE business_id = b.id
                     AND active AND deleted_at IS NULL AND currency = 'INR') AS starting_amount,
                    ARRAY(SELECT badge FROM business_badges WHERE business_id = b.id ORDER BY badge) AS badges,
                    EXISTS(SELECT 1 FROM saved_businesses WHERE business_id = b.id AND user_id = :viewer) AS saved,
                    EXISTS(SELECT 1 FROM follows WHERE business_id = b.id AND user_id = :viewer) AS followed,
                """ + distance + """
                 AS distance_km
                FROM businesses b JOIN categories c ON c.id = b.category_id WHERE b.status = 'ACTIVE'
                """;
    }

    private String searchSql(Search search) {
        String sql = "WITH providers AS (" + projection(search.lat() != null) + ") SELECT * FROM providers WHERE true";
        if (search.category() != null) sql += " AND category = :category";
        if (search.city() != null) sql += " AND lower(city) = lower(:city)";
        if (search.q() != null) sql += " AND (strpos(lower(business_name), lower(:q)) > 0 OR strpos(lower(neighbourhood), lower(:q)) > 0)";
        if (search.maxDistance() != null) sql += " AND distance_km <= :maxDistance";
        if (search.minRating() != null) sql += " AND rating_avg >= :minRating";
        if (search.maxPrice() != null) sql += " AND starting_amount <= :maxPrice";
        if (search.verified()) sql += " AND verification_status = 'VERIFIED'";
        return sql;
    }

    public List<ProviderSummary> search(Search search) {
        String order = switch (search.sort()) {
            case "NEAREST" -> "distance_km ASC NULLS LAST, rating_avg DESC";
            case "PRICE_LOW" -> "starting_amount ASC NULLS LAST";
            case "PRICE_HIGH" -> "starting_amount DESC NULLS LAST";
            case "RATING" -> "rating_avg DESC, review_count DESC";
            default -> "rating_avg DESC, review_count DESC";
        };
        return dbHelper.getRows(searchSql(search) + " ORDER BY " + order + ", id LIMIT :size OFFSET :offset",
                parameters(search).addValue("size", search.size()).addValue("offset", (long) search.page() * search.size()),
                this::summary);
    }

    public long count(Search search) {
        return dbHelper.getSingleField("SELECT count(*) FROM (" + searchSql(search) + ") matches", parameters(search), Long.class);
    }

    public ProviderRow provider(UUID id, UUID viewer) {
        var rows = dbHelper.getRows(projection(false) + " AND b.id = :id",
                new MapSqlParameterSource("id", id).addValue("viewer", viewer, Types.OTHER),
                (rs, row) -> new ProviderRow(summary(rs, row), rs.getString("bio"), rs.getString("avatar_url"),
                        rs.getString("work_mode"), rs.getBoolean("followed")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Offer offer(UUID provider, UUID service) {
        var rows = dbHelper.getRows("""
                SELECT s.duration_minutes, b.by_appointment_only FROM services s JOIN businesses b ON b.id = s.business_id
                WHERE s.id = :service AND b.id = :provider AND b.status = 'ACTIVE' AND s.active AND s.deleted_at IS NULL
                """, new MapSqlParameterSource("provider", provider).addValue("service", service),
                (rs, row) -> new Offer(rs.getInt("duration_minutes"), rs.getBoolean("by_appointment_only")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public ProviderPage collection(UUID user, String category, boolean following, com.findlocally.api.utility.Pages page) {
        String table = following ? "follows" : "saved_businesses";
        String sql = projection(false) + " AND EXISTS(SELECT 1 FROM " + table + " WHERE business_id = b.id AND user_id = :viewer)"
                + (category == null ? "" : " AND c.slug = :category");
        var params = new MapSqlParameterSource("viewer", user).addValue("category", category);
        long count = dbHelper.getSingleField("SELECT count(*) FROM (" + sql + ") selected", params, Long.class);
        return new ProviderPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(dbHelper.getRows(sql + " ORDER BY b.business_name, b.id LIMIT :size OFFSET :offset",
                        params.addValue("size", page.size()).addValue("offset", page.offset()), this::summary));
    }

    public List<Hours> hours(UUID provider) {
        return dbHelper.getRows("SELECT * FROM business_hours WHERE business_id = :provider",
                new MapSqlParameterSource("provider", provider), (rs, row) -> new Hours(rs.getString("day"),
                        rs.getBoolean("is_open"), rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)));
    }

    public Set<LocalDate> blocked(UUID provider, LocalDate start, LocalDate end) {
        return new HashSet<>(dbHelper.getRows("""
                SELECT blocked_on FROM business_blocked_dates WHERE business_id = :provider AND blocked_on BETWEEN :start AND :end
                """, new MapSqlParameterSource("provider", provider).addValue("start", start).addValue("end", end).addValue("now",OffsetDateTime.now(clock)),
                (rs, row) -> rs.getObject("blocked_on", LocalDate.class)));
    }

    public List<Busy> busy(UUID provider, OffsetDateTime start, OffsetDateTime end) {
        return dbHelper.getRows("""
                SELECT starts_at, ends_at FROM bookings WHERE business_id = :provider
                  AND status IN ('AWAITING_CONFIRMATION', 'AWAITING_CUSTOMER_APPROVAL', 'CONFIRMED') AND (status='CONFIRMED' OR response_deadline > :now) AND starts_at < :end AND ends_at > :start
                """, new MapSqlParameterSource("provider", provider).addValue("start", start).addValue("end", end).addValue("now",OffsetDateTime.now(clock)),
                (rs, row) -> new Busy(rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant()));
    }

    private ProviderSummary summary(ResultSet rs, int row) throws SQLException {
        var summary = new ProviderSummary().id(rs.getObject("id", UUID.class)).businessName(rs.getString("business_name"))
                .ownerName(rs.getString("owner_name")).category(rs.getString("category")).neighbourhood(rs.getString("neighbourhood"))
                .tags(strings(rs, "tags")).badges(strings(rs, "badges").stream().map(Badge::fromValue).toList())
                .rating(rs.getFloat("rating_avg")).reviewCount(rs.getInt("review_count")).saved(rs.getBoolean("saved"));
        String cover = rs.getString("cover_image_url");
        if (cover != null) summary.setCoverImageUrl(URI.create(cover));
        BigDecimal distance = rs.getBigDecimal("distance_km");
        if (distance != null) summary.setDistanceKm(distance);
        Long amount = rs.getObject("starting_amount", Long.class);
        if (amount != null) summary.setStartingPrice(new Money(amount, "INR"));
        return summary;
    }

    private List<String> strings(ResultSet rs, String field) throws SQLException {
        var array = rs.getArray(field);
        try { return Arrays.asList((String[]) array.getArray()); }
        finally { array.free(); }
    }

    private MapSqlParameterSource parameters(Search search) {
        return new MapSqlParameterSource().addValue("viewer", search.viewer(), Types.OTHER)
                .addValue("category", search.category()).addValue("city", search.city()).addValue("q", search.q())
                .addValue("lat", search.lat(), Types.DOUBLE).addValue("lng", search.lng(), Types.DOUBLE)
                .addValue("maxDistance", search.maxDistance()).addValue("minRating", search.minRating()).addValue("maxPrice", search.maxPrice());
    }
}

