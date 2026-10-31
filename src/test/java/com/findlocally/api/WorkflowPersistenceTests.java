package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.storage.ObjectStorage;
import com.findlocally.api.service.PasswordResetSender;
import org.mockito.ArgumentCaptor;
import java.io.InputStream;
import org.springframework.mock.web.MockMultipartFile;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises real migrations, triggers, JWT filters and HTTP controllers; external storage and email are mocked. */
@EnabledIfSystemProperty(named = "workflows.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "app.media.public-base-url=https://media.example.com/"})
@Transactional
class WorkflowPersistenceTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired WorkflowClock clock;
    @MockitoBean ObjectStorage storage;
    @MockitoBean PasswordResetSender resetSender;
    private MockMvc mvc;
    private static final String PASSWORD = "a strong workflow password";

    @TestConfiguration
    static class Time {
        @Bean @Primary WorkflowClock workflowClock() { return new WorkflowClock(); }
    }

    static class WorkflowClock extends Clock {
        private Instant now = Instant.parse("2030-10-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setup() {
        clock.now = Instant.parse("2030-10-01T00:00:00Z");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void registrationToPublishedBusinessCheckoutMessagingCompletionReviewAndLogout() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String ownerEmail = "owner-" + suffix + "@example.com", customerEmail = "customer-" + suffix + "@example.com";
        var owner = register(ownerEmail, "Owner");
        var customer = register(customerEmail, "Customer");
        String ownerToken = owner.getAccessToken(), customerToken = customer.getAccessToken();

        call("POST", "/business/onboarding", ownerToken, null, 201);
        call("POST", "/business/onboarding/submit", ownerToken, null, 400);
        step("ABOUT", ownerToken, Map.of("ownerName", "Owner", "businessName", "Workflow Studio", "category", "makeup"));
        step("LOCATION", ownerToken, Map.of("city", "Pune", "neighbourhood", "Koregaon Park", "workMode", "STUDIO"));
        var serviceInput = new ServiceInput().name("Makeup").durationMinutes(60).price(new Money(50000L, "INR"))
                .fulfilment(List.of(Fulfilment.AT_STUDIO));
        step("SERVICES", ownerToken, new OnboardingServices().services(List.of(serviceInput)));
        step("AVAILABILITY", ownerToken, Map.of("weekly", List.of(Map.of("day", "MON", "open", true, "from", "09:00", "to", "18:00"))));
        UUID photo = upload(ownerToken, "GALLERY_PHOTO", "image/jpeg");
        UUID document = upload(ownerToken, "ID_DOCUMENT", "application/pdf");
        step("WORK", ownerToken, Map.of("galleryUploadIds", List.of(photo)));
        step("VERIFICATION", ownerToken, Map.of("idType", "PASSPORT", "idDocumentUploadId", document));
        var preview = mapper.readValue(call("GET", "/business/onboarding/preview", ownerToken, null, 200), ProviderDetail.class);
        UUID provider = preview.getId(), service = preview.getServices().getFirst().getId();
        call("GET", "/providers/" + provider, customerToken, null, 404);
        var submitted = mapper.readValue(call("POST", "/business/onboarding/submit", ownerToken, null, 200), OnboardingState.class);
        assertEquals(OnboardingState.StatusEnum.SUBMITTED, submitted.getStatus());
        assertEquals(6, submitted.getCompletedSteps().size());
        assertEquals(0, context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, provider));
        var publicProfile = mapper.readValue(call("GET", "/providers/" + provider + "/profile", customerToken, null, 200), ProviderSocialProfile.class);
        assertEquals("Owner", publicProfile.getDisplayName());
        var gallery = mapper.readValue(call("GET", "/providers/" + provider + "/media", customerToken, null, 200), MediaPage.class);
        assertEquals(1, gallery.getTotalElements());
        assertFalse(gallery.getContent().getFirst().getUrl().toString().contains("private/"));
        verify(storage, times(2)).upload(anyString(), anyString(), anyLong(), any(InputStream.class));
        verify(storage, never()).verify(anyString(), anyString(), anyLong());
        call("GET", "/providers/" + provider + "/availability?serviceId=" + service + "&date=2030-10-07", customerToken, null, 200);

        call("PUT", "/users/me/saved/" + provider, customerToken, null, 204);
        call("PUT", "/providers/" + provider + "/follow", customerToken, null, 204);
        var unscheduled = new CartItemInput().providerId(provider).serviceId(service);
        var cart = mapper.readValue(call("POST", "/cart/items", customerToken, unscheduled, 201), Cart.class);
        UUID item = cart.getItems().getFirst().getId();
        call("PUT", "/cart/items/" + item, ownerToken, unscheduled, 404);
        var scheduled = new CartItemInput().providerId(provider).serviceId(service).startsAt(OffsetDateTime.parse("2030-10-07T10:00:00+05:30"))
                .fulfilment(Fulfilment.AT_STUDIO);
        call("PUT", "/cart/items/" + item, customerToken, scheduled, 200);
        UUID key = UUID.randomUUID();
        String checkedOut = call("POST", "/cart/checkout", customerToken, null, 201, key);
        assertEquals(checkedOut, call("POST", "/cart/checkout", customerToken, null, 201, key));
        UUID booking = mapper.readValue(checkedOut, Booking[].class)[0].getId();
        assertTrue(mapper.readValue(call("GET", "/cart", customerToken, null, 200), Cart.class).getItems().isEmpty());
        call("POST", "/business/bookings/" + booking + "/accept", customerToken, null, 403);
        call("POST", "/business/bookings/" + booking + "/accept", ownerToken, null, 200);

        var conversation = mapper.readValue(call("POST", "/conversations", customerToken,
                Map.of("providerId", provider, "bookingId", booking), 200), Conversation.class);
        call("POST", "/conversations/" + conversation.getId() + "/messages", customerToken, Map.of("text", "See you soon"), 201);
        var inbox = mapper.readValue(call("GET", "/conversations", ownerToken, null, 200), ConversationPage.class);
        assertEquals(1, inbox.getContent().getFirst().getUnreadCount());
        call("POST", "/conversations/" + conversation.getId() + "/read", ownerToken, null, 204);
        assertTrue(mapper.readValue(call("GET", "/notifications?unreadOnly=true", ownerToken, null, 200), NotificationPage.class).getTotalElements() >= 2);
        call("POST", "/notifications/read-all", ownerToken, null, 204);

        clock.now = Instant.parse("2030-10-07T07:00:00Z");
        ownerToken = login(ownerEmail).getAccessToken();
        customerToken = login(customerEmail).getAccessToken();
        call("POST", "/business/bookings/" + booking + "/complete", ownerToken, null, 200);
        var review = mapper.readValue(call("POST", "/bookings/" + booking + "/review", customerToken,
                Map.of("rating", 5, "text", "Excellent"), 201), Review.class);
        call("PUT", "/business/reviews/" + review.getId() + "/reply", ownerToken, Map.of("text", "Thank you"), 200);
        var rated = mapper.readValue(call("GET", "/providers/" + provider, customerToken, null, 200), ProviderDetail.class);
        assertEquals(1, rated.getReviewCount());
        assertEquals(5.0f, rated.getRating());
        var earnings = mapper.readValue(call("GET", "/business/earnings?month=2030-10", ownerToken, null, 200), Earnings.class);
        assertEquals(50000L, earnings.getTotal().getAmount());
        call("GET", "/business/dashboard/overview", ownerToken, null, 200);
        call("POST", "/auth/logout", customerToken, null, 204);
        call("GET", "/users/me", customerToken, null, 401);
        when(resetSender.send(anyString(), anyString())).thenReturn(true);
        call("POST", "/auth/forgot-password", null, Map.of("email", customerEmail), 202);
        var resetToken = ArgumentCaptor.forClass(String.class);
        verify(resetSender).send(eq(customerEmail), resetToken.capture());
        call("POST", "/auth/reset-password", null,
                Map.of("token", resetToken.getValue(), "newPassword", "a new workflow password"), 204);
        call("POST", "/auth/login", null, Map.of("email", customerEmail, "password", PASSWORD), 401);
        call("POST", "/auth/login", null, Map.of("email", customerEmail, "password", "a new workflow password"), 200);
    }

    private AuthResponse register(String email, String name) throws Exception {
        return mapper.readValue(call("POST", "/auth/register", null, Map.of("email", email, "password", PASSWORD, "name", name, "accountType", name.equals("Owner")?"BUSINESS":"CUSTOMER"), 201), AuthResponse.class);
    }
    private AuthResponse login(String email) throws Exception {
        return mapper.readValue(call("POST", "/auth/login", null, Map.of("email", email, "password", PASSWORD), 200), AuthResponse.class);
    }
    private UUID upload(String token, String purpose, String contentType) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/media/uploads")
                        .file(new MockMultipartFile("file", "upload", contentType, contentType.equals("application/pdf") ? "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII) : TestMedia.jpeg())).param("purpose", purpose)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(result, UploadResponse.class).getUploadId();
    }
    private void step(String name, String token, Object body) throws Exception {
        call("PUT", "/business/onboarding/steps/" + name, token, body, 200);
    }
    private String call(String method, String path, String token, Object body, int expected) throws Exception {
        return call(method, path, token, body, expected, UUID.randomUUID());
    }
    private String call(String method, String path, String token, Object body, int expected, UUID key) throws Exception {
        var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1" + path).header("Idempotency-Key", key);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
}
