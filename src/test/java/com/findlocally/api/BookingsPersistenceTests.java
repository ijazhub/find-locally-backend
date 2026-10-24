package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.BookingsService;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in PostgreSQL checks; every table is temporary and all test writes roll back. */
@EnabledIfSystemProperty(named = "bookings.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
@Transactional
class BookingsPersistenceTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingsService bookings;
    @Autowired PlatformTransactionManager transactions;
    private final UUID customer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID providerOwner = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary
        Clock testClock() { return Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC); }
    }

    @BeforeEach
    void temporaryTablesAndFixtures() throws Exception {
        String schema = new ClassPathResource("db/migration/V1__init_schema.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Set<String> needed = Set.of("users", "categories", "businesses", "business_badges", "services", "addresses",
                "business_hours", "business_blocked_dates", "saved_businesses", "bookings", "cart_items",
                "idempotency_keys", "reviews", "conversations", "notifications");
        var tables = Pattern.compile("CREATE TABLE (\\w+) \\([\\s\\S]*?\\r?\\n\\);").matcher(schema);
        int count = 0;
        while (tables.find()) {
            if (!needed.contains(tables.group(1))) continue;
            String ddl = tables.group().replaceFirst("CREATE TABLE", "CREATE TEMP TABLE");
            jdbc.execute(ddl.substring(0, ddl.length() - 2) + ") ON COMMIT DROP");
            count++;
        }
        assertEquals(needed.size(), count);
        jdbc.execute("ALTER TABLE pg_temp.idempotency_keys ADD COLUMN request_hash varchar(64)");
        jdbc.update("INSERT INTO pg_temp.users (id, phone, name) VALUES (?, '+919876543210', 'Customer')", customer);
        jdbc.update("INSERT INTO pg_temp.users (id, phone, name) VALUES (?, '+919876543211', 'Other')", other);
        jdbc.update("INSERT INTO pg_temp.users (id, phone, name) VALUES (?, '+919876543212', 'Provider')", providerOwner);
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.categories (id, slug, name) VALUES (?, 'makeup', 'Makeup')", category);
        jdbc.update("""
                INSERT INTO pg_temp.businesses (id, owner_user_id, status, category_id, business_name, city, neighbourhood)
                VALUES (?, ?, 'ACTIVE', ?, 'Asha Studio', 'Pune', 'Koregaon Park')
                """, provider, providerOwner, category);
        jdbc.update("""
                INSERT INTO pg_temp.services (id, business_id, name, duration_minutes, price_amount, fulfilment)
                VALUES (?, ?, 'Makeup', 60, 50000, '{AT_STUDIO,AT_CLIENT}')
                """, serviceId, provider);
        jdbc.update("""
                INSERT INTO pg_temp.business_hours (business_id, day, open_time, close_time)
                VALUES (?, 'MON', '09:00', '18:00')
                """, provider);
        jdbc.update("""
                INSERT INTO pg_temp.addresses (id, user_id, line1, city, pincode)
                VALUES (?, ?, 'Original address', 'Pune', '411001')
                """, addressId, customer);
        signIn(customer);
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    private void signIn(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private BookingCreate input() {
        return new BookingCreate().providerId(provider).serviceId(serviceId)
                .startsAt(OffsetDateTime.parse("2026-10-19T10:00:00+05:30")).fulfilment(Fulfilment.AT_STUDIO);
    }

    private void failure(HttpStatus status, Runnable operation) {
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        var exception = assertThrows(ResponseStatusException.class,
                () -> nested.executeWithoutResult(tx -> operation.run()));
        assertEquals(status, exception.getStatusCode());
    }

    @Test
    void createsSnapshotsAndReplaysEvenAfterServiceAndAddressChange() {
        UUID key = UUID.randomUUID();
        var input = input().fulfilment(Fulfilment.AT_CLIENT).addressId(addressId).note("Please call");
        Booking created = bookings.createBooking(key, input);
        assertEquals(BookingStatus.AWAITING_CONFIRMATION, created.getStatus());
        assertEquals(50000L, created.getTotal().getAmount());
        assertEquals(input.getStartsAt().plusHours(1).toInstant(), created.getEndsAt().toInstant());
        jdbc.update("UPDATE pg_temp.services SET name = 'Changed', price_amount = 90000, deleted_at = now(), active = false WHERE id = ?", serviceId);
        jdbc.update("DELETE FROM pg_temp.addresses WHERE id = ?", addressId);
        Booking retrieved = bookings.getBooking(created.getId());
        assertEquals("Makeup", retrieved.getService().getName());
        assertEquals("Original address", retrieved.getAddress().getLine1());
        assertEquals(50000L, retrieved.getTotal().getAmount());
        Booking replay = bookings.createBooking(key, input);
        assertEquals(created, replay);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
        assertEquals(201, jdbc.queryForObject("SELECT response_status FROM pg_temp.idempotency_keys", Integer.class));
    }

    @Test
    void changedPayloadCannotReuseKeyAndOffsetsAreNormalized() {
        UUID key = UUID.randomUUID();
        var created = bookings.createBooking(key, input());
        var utc = input().startsAt(input().getStartsAt().withOffsetSameInstant(ZoneOffset.UTC));
        assertEquals(created, bookings.createBooking(key, utc));
        failure(HttpStatus.CONFLICT, () -> bookings.createBooking(key, input().note("Different")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
    }

    @Test
    void overlapConstraintRejectsDoubleBookingAndFailedKeyRollsBack() {
        bookings.createBooking(UUID.randomUUID(), input());
        UUID failedKey = UUID.randomUUID();
        signIn(other);
        failure(HttpStatus.CONFLICT, () -> bookings.createBooking(failedKey, input()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.idempotency_keys WHERE key = ?", Integer.class, failedKey));
        // Adjacent intervals do not overlap.
        assertNotNull(bookings.createBooking(failedKey, input().startsAt(input().getStartsAt().plusHours(1))).getId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
    }

    @Test
    void foreignReadAndCancellationAreHiddenAndCancellationFreesSlot() {
        var booking = bookings.createBooking(UUID.randomUUID(), input());
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> bookings.getBooking(booking.getId()));
        failure(HttpStatus.NOT_FOUND, () -> bookings.cancelBooking(booking.getId(), null));
        signIn(customer);
        assertEquals(BookingStatus.CANCELLED,
                bookings.cancelBooking(booking.getId(), new CancelBookingRequest().reason("Plans changed")).getStatus());
        assertEquals(BookingStatus.CANCELLED, bookings.cancelBooking(booking.getId(), null).getStatus());
        assertEquals("Plans changed", jdbc.queryForObject("SELECT cancel_reason FROM pg_temp.bookings WHERE id = ?", String.class, booking.getId()));
        assertNotNull(bookings.createBooking(UUID.randomUUID(), input()).getId());
    }

    @Test
    void filtersAndSummaryHaveMatchingCountsAndMetadata() {
        var first = bookings.createBooking(UUID.randomUUID(), input());
        var second = bookings.createBooking(UUID.randomUUID(), input().startsAt(input().getStartsAt().plusHours(1)));
        var third = bookings.createBooking(UUID.randomUUID(), input().startsAt(input().getStartsAt().plusHours(2)));
        jdbc.update("UPDATE pg_temp.bookings SET status = 'COMPLETED' WHERE id = ?", second.getId());
        jdbc.update("UPDATE pg_temp.bookings SET status = 'DECLINED' WHERE id = ?", third.getId());
        jdbc.update("INSERT INTO pg_temp.cart_items (user_id, business_id, service_id) VALUES (?, ?, ?)", customer, provider, serviceId);
        jdbc.update("INSERT INTO pg_temp.reviews (booking_id, business_id, author_id, rating) VALUES (?, ?, ?, 5)",
                second.getId(), provider, customer);
        UUID conversation = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.conversations (id, customer_id, business_id) VALUES (?, ?, ?)", conversation, customer, provider);
        assertEquals(first.getId(), bookings.listMyBookings("UPCOMING", 0, 1).getContent().getFirst().getId());
        assertTrue(bookings.listMyBookings("DONE", 0, 1).getContent().getFirst().getReviewed());
        assertEquals(conversation, bookings.getBooking(first.getId()).getConversationId());
        assertEquals(1L, bookings.listMyBookings("CANCELLED", 0, 1).getTotalElements());
        assertTrue(bookings.listMyBookings("UPCOMING", 5, 1).getContent().isEmpty());
        var summary = bookings.getBookingSummary();
        assertEquals(1, summary.getUpcomingCount());
        assertEquals(1, summary.getDoneCount());
        assertEquals(1, summary.getCancelledCount());
        assertEquals(1, summary.getCartCount());
        assertEquals(first.getStartsAt().toInstant(), summary.getNextBookingAt().toInstant());
        assertTrue(summary.getNextBookingLabel().contains("Monday"));
        signIn(other);
        assertEquals(0, bookings.getBookingSummary().getUpcomingCount());
        assertNull(bookings.getBookingSummary().getNextBookingAt());
    }

    @Test
    void rejectsUnownedAddressAndBlockedOrClosedDates() {
        signIn(other);
        failure(HttpStatus.BAD_REQUEST, () -> bookings.createBooking(UUID.randomUUID(),
                input().fulfilment(Fulfilment.AT_CLIENT).addressId(addressId)));
        signIn(customer);
        failure(HttpStatus.CONFLICT, () -> bookings.createBooking(UUID.randomUUID(),
                input().startsAt(OffsetDateTime.parse("2026-10-20T10:00:00+05:30"))));
        jdbc.update("INSERT INTO pg_temp.business_blocked_dates (business_id, blocked_on) VALUES (?, '2026-10-19')", provider);
        failure(HttpStatus.CONFLICT, () -> bookings.createBooking(UUID.randomUUID(), input()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
    }
}
