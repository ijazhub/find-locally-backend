package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.CatalogService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "services.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
@Transactional
class CatalogPersistenceTests extends TemporaryDatabase {
    @Autowired CatalogService catalog;
    private final UUID viewer = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();
    private UUID near, far, draft, service;
    private final LocalDate monday = LocalDate.of(2026, 10, 19);

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC); }
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO pg_temp.users (id, phone) VALUES (?, '+919876543210')", viewer);
        jdbc.update("INSERT INTO pg_temp.user_roles(user_id,role) VALUES(?, 'CUSTOMER')",viewer);
        jdbc.update("INSERT INTO pg_temp.categories (id, slug, name) VALUES (?, 'makeup', 'Makeup')", category);
        near = provider("Near Studio", "ACTIVE", 18.53, 73.90, "VERIFIED");
        far = provider("Far Studio", "ACTIVE", 19.07, 72.87, "PENDING");
        draft = provider("Draft Studio", "DRAFT", 18.53, 73.90, "PENDING");
        service = service(near, 50000, true);
        service(far, 90000, true);
        service(near, 1000, false);
        jdbc.update("INSERT INTO pg_temp.business_hours (business_id, day, open_time, close_time) VALUES (?, 'MON', '09:00', '12:00')", near);
    }

    private UUID provider(String name, String status, double lat, double lng, String verification) {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.users (id, phone) VALUES (?, ?)", owner, "+" + Math.abs((long) owner.hashCode()));
        jdbc.update("INSERT INTO pg_temp.user_roles(user_id,role) VALUES(?, 'BUSINESS')",owner);
        jdbc.update("""
                INSERT INTO pg_temp.businesses (id, owner_user_id, status, business_name, category_id, city, neighbourhood,
                    lat, lng, verification_status, work_mode, bio)
                VALUES (?, ?, ?, ?, ?, 'Pune', 'Koregaon Park', ?, ?, ?, 'STUDIO', 'Artist')
                """, id, owner, status, name, category, lat, lng, verification);
        return id;
    }

    private UUID service(UUID business, long amount, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.services (id, business_id, name, duration_minutes, price_amount, active) VALUES (?, ?, 'Makeup', 60, ?, ?)",
                id, business, amount, active);
        return id;
    }

    private ProviderPage search(String sort, Double lat, Double lng, BigDecimal distance, Long price, Boolean verified) {
        return catalog.searchProviders("makeup", null, "Pune", lat, lng, distance, null, price, verified, sort, 0, 20);
    }

    @Test
    void activeOnlySearchSupportsDistancePriceAndVerification() {
        var nearest = search("NEAREST", 18.53, 73.90, null, null, false);
        assertEquals(2, nearest.getContent().size());
        assertEquals(near, nearest.getContent().getFirst().getId());
        assertEquals(0, nearest.getContent().getFirst().getDistanceKm().compareTo(BigDecimal.ZERO));
        assertEquals(1L, search("NEAREST", 18.53, 73.90, BigDecimal.valueOf(5), null, false).getTotalElements());
        assertEquals(1L, search("PRICE_LOW", null, null, null, 60000L, false).getTotalElements());
        assertEquals(1L, search("RATING", null, null, null, null, true).getTotalElements());
        assertEquals(far, search("PRICE_HIGH", null, null, null, null, false).getContent().getFirst().getId());
        assertThrows(ResponseStatusException.class, () -> nested(() -> catalog.getProvider(draft)));
    }

    @Test
    void anonymousAndSignedInCatalogFlagsAreAccurate() {
        assertFalse(catalog.getProvider(near).getSaved());
        jdbc.update("INSERT INTO pg_temp.saved_businesses (user_id, business_id) VALUES (?, ?)", viewer, near);
        jdbc.update("INSERT INTO pg_temp.follows (user_id, business_id) VALUES (?, ?)", viewer, near);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(viewer.toString(), null, List.of()));
        var detail = catalog.getProvider(near);
        assertTrue(detail.getSaved());
        assertTrue(detail.getFollowed());
        assertEquals("Artist", detail.getBio());
        assertEquals(1, detail.getServices().size());
        assertEquals(50000L, detail.getStartingPrice().getAmount());
        assertEquals(2, catalog.getHome("Pune").getFeatured().size());
        assertEquals(2, catalog.listCategories().getFirst().getProviderCount());
    }

    @Test
    void calendarAndSlotsRespectDurationBusyIntervalsAndBlockedDays() {
        var slots = catalog.getProviderSlots(near, service, monday);
        assertEquals(5, slots.size());
        assertTrue(slots.stream().allMatch(Slot::getAvailable));
        jdbc.update("""
                INSERT INTO pg_temp.bookings (customer_id, business_id, service_id, starts_at, ends_at,
                    fulfilment, service_name, duration_minutes, price_amount,proposed_amount,hold_expires_at,response_deadline)
                VALUES (?, ?, ?, '2026-10-19 04:30:00+00', '2026-10-19 05:30:00+00', 'AT_STUDIO', 'Makeup', 60, 50000,50000,'2026-10-16T00:00:00Z','2026-10-16T00:00:00Z')
                """, viewer, near, service);
        var busy = catalog.getProviderSlots(near, service, monday);
        assertEquals(List.of(true, false, false, false, true), busy.stream().map(Slot::getAvailable).toList());
        assertTrue(catalog.getProviderCalendar(near, service, "2026-10").getAvailableDates().contains(monday));
        jdbc.update("INSERT INTO pg_temp.business_blocked_dates (business_id, blocked_on) VALUES (?, ?)", near, monday);
        assertTrue(catalog.getProviderSlots(near, service, monday).isEmpty());
        assertFalse(catalog.getProviderCalendar(near, service, "2026-10").getAvailableDates().contains(monday));
    }

    @Test
    void appointmentOnlyClosedAndForeignServiceHaveNoAutomaticSlots() {
        assertTrue(catalog.getProviderSlots(near, service, monday.plusDays(1)).isEmpty());
        jdbc.update("UPDATE pg_temp.businesses SET by_appointment_only = true WHERE id = ?", near);
        assertTrue(catalog.getProviderSlots(near, service, monday).isEmpty());
        assertThrows(ResponseStatusException.class, () -> nested(() -> catalog.getProviderSlots(far, service, monday)));
    }

    @Test
    void validatesSearchAndTreatsQueryAsLiteralText() {
        assertThrows(ResponseStatusException.class, () -> nested(() -> search("NEAREST", null, null, null, null, false)));
        assertThrows(ResponseStatusException.class, () -> nested(() -> search("NEAREST", Double.NaN, 0.0, null, null, false)));
        var literal = catalog.searchProviders(null, "' OR true --", null, null, null, null, null, null, null, null, null, null);
        assertEquals(0, literal.getTotalElements());
        assertThrows(ResponseStatusException.class, () -> nested(() -> catalog.getProviderCalendar(near, service, "2026-13")));
    }
}
