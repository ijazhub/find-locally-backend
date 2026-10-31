package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.BusinessOnboardingRepository;
import com.findlocally.api.service.BusinessOnboardingService;
import com.findlocally.api.service.BusinessServicesService;
import java.time.LocalDate;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in PostgreSQL checks: -Dbusiness.postgres.tests=true.
 * All tables are temporary copies of V1; the enclosing transaction always rolls back.
 */
@EnabledIfSystemProperty(named = "business.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "app.media.public-base-url=https://media.example.com/"})
@Transactional
class BusinessPersistenceTests extends TemporaryDatabase {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessOnboardingService onboarding;
    @Autowired BusinessServicesService services;
    @Autowired BusinessOnboardingRepository repository;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void temporaryTables() throws Exception {
        jdbc.update("INSERT INTO pg_temp.users (id, phone) VALUES (?, ?)", owner, "+919876543210");
        jdbc.update("INSERT INTO pg_temp.user_roles(user_id,role) VALUES(?, 'BUSINESS')",owner);
        jdbc.update("INSERT INTO pg_temp.categories (slug, name) VALUES ('makeup', 'Makeup')");
        signIn(owner);
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    private void signIn(UUID user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
    }

    private ServiceInput serviceInput(String name) {
        return new ServiceInput().name(name).durationMinutes(60)
                .price(new Money(50000L, "INR")).fulfilment(List.of(Fulfilment.AT_STUDIO));
    }

    @Test
    void wizardRoundTripsEveryStepAndPublishesRelationalProfile() {
        onboarding.startOnboarding();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.user_roles WHERE role = 'BUSINESS'", Integer.class));
        onboarding.saveOnboardingStep("ABOUT", new OnboardingAbout()
                .ownerName("Asha").businessName("Asha Studio").category("makeup").bio("Local makeup artist"));
        onboarding.saveOnboardingStep("LOCATION", new OnboardingLocation()
                .city("Pune").neighbourhood("Koregaon Park").workMode(OnboardingLocation.WorkModeEnum.STUDIO)
                .location(new GeoPoint().lat(18.53).lng(73.90)));
        onboarding.saveOnboardingStep("SERVICES", new OnboardingServices().services(List.of(serviceInput("Makeup")))
                .budgetRange(new OnboardingServicesBudgetRange().min(new Money(10000L, "INR")).max(new Money(80000L, "INR"))));
        onboarding.saveOnboardingStep("AVAILABILITY", new AvailabilityInput().byAppointmentOnly(false)
                .weekly(List.of(new AvailabilityInputWeeklyInner().day(AvailabilityInputWeeklyInner.DayEnum.MON)
                        .open(true).from("09:00").to("18:00")))
                .blockedDates(List.of(LocalDate.of(2026, 12, 25))));
        UUID avatar = upload("AVATAR", "avatars/asha.png", "image/png");
        UUID photo = upload("GALLERY_PHOTO", "gallery/work.png", "image/png");
        UUID document = upload("ID_DOCUMENT", "private/pan.png", "image/png");
        onboarding.saveOnboardingStep("WORK", new OnboardingWork().avatarUploadId(avatar).galleryUploadIds(List.of(photo)));
        onboarding.saveOnboardingStep("VERIFICATION", new OnboardingVerification()
                .idType(OnboardingVerification.IdTypeEnum.PAN).idDocumentUploadId(document));
        var saved = onboarding.getOnboarding();
        assertEquals(6, saved.getCompletedSteps().size());
        assertEquals(avatar, saved.getWork().getAvatarUploadId());
        assertEquals("09:00", saved.getAvailability().getWeekly().getFirst().getFrom());
        assertEquals(50000L, saved.getServices().getServices().getFirst().getPrice().getAmount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.business_hours", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.business_blocked_dates", Integer.class));
        var preview = onboarding.getOnboardingPreview();
        assertEquals("Asha Studio", preview.getBusinessName());
        assertEquals("https://media.example.com/avatars/asha.png", preview.getAvatarUrl().toString());
        assertEquals(50000L, preview.getStartingPrice().getAmount());
        assertEquals(OnboardingState.StatusEnum.SUBMITTED, onboarding.submitOnboarding().getStatus());
        assertEquals(OnboardingState.StatusEnum.SUBMITTED, onboarding.getOnboarding().getStatus());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM pg_temp.businesses", String.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM pg_temp.business_verifications", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.outbox_events", Integer.class));
    }

    @Test
    void serviceCrudScopesOwnershipAndSoftDeletesRows() {
        onboarding.startOnboarding();
        var created = services.createService(serviceInput("Initial"));
        assertEquals(List.of(Fulfilment.AT_STUDIO), created.getFulfilment());
        assertTrue(services.setServiceActive(created.getId(), new SetServiceActiveRequest().active(false)).getActive() == false);
        assertEquals("Updated", services.updateService(created.getId(), serviceInput("Updated")).getName());
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.users (id, phone) VALUES (?, ?)", other, "+919876543211");
        jdbc.update("INSERT INTO pg_temp.user_roles(user_id,role) VALUES(?, 'BUSINESS')",other);
        repository.create(other);
        signIn(other);
        // A failed service call marks the shared test transaction rollback-only; raw repository
        // calls below verify foreign mutations without affecting later service checks.
        var businessServices = new com.findlocally.api.repository.BusinessServicesRepository(
                new com.findlocally.api.utility.DbHelper(
                        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)));
        UUID otherBusiness = businessServices.findBusinessId(other);
        assertNull(businessServices.update(otherBusiness, created.getId(), serviceInput("Foreign")));
        assertNull(businessServices.setActive(otherBusiness, created.getId(), true));
        assertEquals(0, businessServices.delete(otherBusiness, created.getId()));
        assertTrue(services.listOwnServices().isEmpty());
        signIn(owner);
        services.deleteService(created.getId());
        assertTrue(services.listOwnServices().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_temp.services WHERE deleted_at IS NOT NULL AND NOT active", Integer.class));
    }

    @Test
    void anonymousIdentityCannotResolveAnOwner() {
        SecurityContextHolder.clearContext();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, services::listOwnServices).getStatusCode());
    }

    private UUID upload(String purpose, String key, String contentType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pg_temp.uploads (id, user_id, purpose, object_key, content_type, size_bytes, status, processing_state)
                VALUES (?, ?, ?, ?, ?, 10, 'UPLOADED','READY')
                """, id, owner, purpose, key, contentType);
        return id;
    }
}
