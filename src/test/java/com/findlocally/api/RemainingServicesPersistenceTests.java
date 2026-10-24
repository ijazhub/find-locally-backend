package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.*;
import com.findlocally.api.storage.ObjectStorage;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "remaining.postgres.tests", matches = "true")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never", "app.media.public-base-url=https://media.example.com/"})
@Transactional
class RemainingServicesPersistenceTests extends TemporaryDatabase {
    @Autowired SavedFollowingService saved;
    @Autowired ReviewsService reviews;
    @Autowired MessagesService messages;
    @Autowired NotificationsService notifications;
    @Autowired CartService cart;
    @Autowired BookingsService bookings;
    @Autowired BusinessBookingsService businessBookings;
    @Autowired BusinessDashboardService dashboard;
    @Autowired ProviderProfileService profiles;
    @Autowired MediaService media;
    @MockitoBean ObjectStorage storage;
    private final UUID customer = UUID.randomUUID(), other = UUID.randomUUID(), owner = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID(), service = UUID.randomUUID();
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-19T10:00:00+05:30");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC); }
    }
    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO pg_temp.users (id, phone, name) VALUES (?, '+919876543210', 'Customer'), (?, '+919876543211', 'Other'), (?, '+919876543212', 'Owner')",
                customer, other, owner);
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.categories (id, slug, name) VALUES (?, 'makeup', 'Makeup')", category);
        jdbc.update("""
                INSERT INTO pg_temp.businesses (id, owner_user_id, status, category_id, business_name, owner_name, city, neighbourhood, work_mode)
                VALUES (?, ?, 'ACTIVE', ?, 'Studio', 'Owner', 'Pune', 'Koregaon Park', 'STUDIO')
                """, provider, owner, category);
        jdbc.update("""
                INSERT INTO pg_temp.services (id, business_id, name, duration_minutes, price_amount, fulfilment)
                VALUES (?, ?, 'Makeup', 60, 50000, '{AT_STUDIO,AT_CLIENT}')
                """, service, provider);
        jdbc.update("INSERT INTO pg_temp.business_hours (business_id, day, open_time, close_time) VALUES (?, 'MON', '09:00', '18:00')", provider);
        signIn(customer);
    }
    private void signIn(UUID user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
    }
    private BookingCreate booking(OffsetDateTime time) {
        return new BookingCreate().providerId(provider).serviceId(service).startsAt(time).fulfilment(Fulfilment.AT_STUDIO);
    }
    private CartItemInput item(OffsetDateTime time) {
        return new CartItemInput().providerId(provider).serviceId(service).startsAt(time).fulfilment(Fulfilment.AT_STUDIO);
    }
    private void failure(HttpStatus status, Runnable call) {
        assertEquals(status, assertThrows(ResponseStatusException.class, () -> nested(call)).getStatusCode());
    }

    @Test
    void savedAndFollowingAreIdempotentPaginatedAndPrivate() {
        saved.saveProvider(provider); saved.saveProvider(provider); saved.followProvider(provider);
        assertEquals(1, saved.listSaved("makeup", 0, 20).getTotalElements());
        assertTrue(saved.listSaved(null, 0, 20).getContent().getFirst().getSaved());
        assertEquals(1, saved.listFollowing(0, 20).getTotalElements());
        signIn(other);
        assertTrue(saved.listFollowing(0, 20).getContent().isEmpty());
        signIn(customer);
        saved.unsaveProvider(provider); saved.unfollowProvider(provider);
        assertEquals(0, saved.listSaved(null, 0, 20).getTotalElements());
    }

    @Test
    void messagingRestrictsParticipantsAndTracksUnreadCounts() {
        var conversation = messages.startConversation(new StartConversationRequest().providerId(provider));
        assertEquals(conversation.getId(), messages.startConversation(new StartConversationRequest().providerId(provider)).getId());
        var sent = messages.sendMessage(conversation.getId(), new SendMessageRequest().text("Hello"));
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> messages.listMessages(conversation.getId(), null, 20));
        failure(HttpStatus.NOT_FOUND, () -> messages.sendMessage(conversation.getId(), new SendMessageRequest().text("Intruder")));
        signIn(owner);
        var inbox = messages.listConversations(0, 20).getContent().getFirst();
        assertEquals("Customer", inbox.getCounterpart().getName());
        assertEquals(1, inbox.getUnreadCount());
        assertEquals(sent.getId(), inbox.getLastMessage().getId());
        messages.markConversationRead(conversation.getId());
        assertEquals(0, messages.listConversations(0, 20).getContent().getFirst().getUnreadCount());
        assertNotNull(messages.listMessages(conversation.getId(), null, 20).getFirst().getReadAt());
        messages.sendMessage(conversation.getId(), new SendMessageRequest().text("Welcome"));
        signIn(customer);
        assertEquals(2, messages.listMessages(conversation.getId(), null, 20).size());
        assertEquals(1, messages.listMessages(conversation.getId(), sent.getSentAt().plusNanos(1000), 20).size());
    }

    @Test
    void notificationsAreOwnedAndReadOperationsAreIdempotent() {
        var conversation = messages.startConversation(new StartConversationRequest().providerId(provider));
        messages.sendMessage(conversation.getId(), new SendMessageRequest().text("Hello"));
        signIn(owner);
        var page = notifications.listNotifications(true, 0, 20);
        UUID id = page.getContent().getFirst().getId();
        assertEquals(1, page.getTotalElements());
        notifications.markNotificationRead(id); notifications.markNotificationRead(id);
        assertEquals(0, notifications.listNotifications(true, 0, 20).getTotalElements());
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> notifications.markNotificationRead(id));
        assertEquals(0, notifications.listNotifications(false, 0, 20).getTotalElements());
        notifications.markAllNotificationsRead();
    }

    @Test
    void cartItemsCanBeScheduledReplacedAndClearedWithoutChangingTheirIds() {
        var added = cart.addCartItem(new CartItemInput().providerId(provider).serviceId(service)).getItems().getFirst();
        failure(HttpStatus.BAD_REQUEST, () -> cart.checkoutCart(UUID.randomUUID()));
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> cart.updateCartItem(added.getId(), item(start)));
        signIn(customer);
        failure(HttpStatus.BAD_REQUEST, () -> cart.updateCartItem(added.getId(), item(start).fulfilment(Fulfilment.DELIVERY)));
        failure(HttpStatus.NOT_FOUND, () -> cart.updateCartItem(added.getId(), item(start).serviceId(UUID.randomUUID())));
        var updated = cart.updateCartItem(added.getId(), item(start)).getItems().getFirst();
        assertEquals(added.getId(), updated.getId());
        assertEquals(start.toInstant(), updated.getStartsAt().toInstant());
        assertEquals(Fulfilment.AT_STUDIO, updated.getFulfilment());
        var cleared = cart.updateCartItem(added.getId(), new CartItemInput().providerId(provider).serviceId(service)).getItems().getFirst();
        assertNull(cleared.getStartsAt());
        assertNull(cleared.getFulfilment());
        cart.updateCartItem(added.getId(), item(start));
        assertEquals(1, cart.checkoutCart(UUID.randomUUID()).size());
    }

    @Test
    void checkoutIsAtomicAndReplaysAfterCartWasCleared() {
        cart.addCartItem(item(start)); cart.addCartItem(item(start.plusHours(1)));
        UUID key = UUID.randomUUID();
        var created = cart.checkoutCart(key);
        assertEquals(2, created.size());
        assertTrue(cart.getCart().getItems().isEmpty());
        assertEquals(created, cart.checkoutCart(key));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
    }

    @Test
    void failedCheckoutRetainsCartAndRollsBackAllBookingsAndKeys() {
        cart.addCartItem(item(start)); cart.addCartItem(item(start.plusMinutes(30)));
        failure(HttpStatus.CONFLICT, () -> cart.checkoutCart(UUID.randomUUID()));
        assertEquals(2, cart.getCart().getItems().size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.bookings", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.idempotency_keys", Integer.class));
    }

    @Test
    void checkoutUsesOwnedDefaultAddressAndRejectsForeignItemRemoval() {
        UUID address = UUID.randomUUID();
        jdbc.update("INSERT INTO pg_temp.addresses (id, user_id, line1, city, pincode, is_default) VALUES (?, ?, 'Home', 'Pune', '411001', true)", address, customer);
        var cartItem = cart.addCartItem(item(start).fulfilment(Fulfilment.AT_CLIENT)).getItems().getFirst();
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> cart.removeCartItem(cartItem.getId()));
        signIn(customer);
        assertEquals(address, cart.checkoutCart(UUID.randomUUID()).getFirst().getAddress().getId());
    }

    @Test
    void providerTransitionsAndReviewsEnforceLifecycleAndOwnership() {
        var created = bookings.createBooking(UUID.randomUUID(), booking(start));
        signIn(other);
        failure(HttpStatus.FORBIDDEN, () -> businessBookings.acceptBooking(created.getId()));
        signIn(owner);
        assertEquals(BookingStatus.CONFIRMED, businessBookings.acceptBooking(created.getId()).getStatus());
        failure(HttpStatus.CONFLICT, () -> businessBookings.completeBooking(created.getId()));
        jdbc.update("UPDATE pg_temp.bookings SET starts_at = '2026-10-14 10:00+00', ends_at = '2026-10-14 11:00+00' WHERE id = ?", created.getId());
        businessBookings.completeBooking(created.getId());
        signIn(other);
        failure(HttpStatus.NOT_FOUND, () -> reviews.createReview(created.getId(), new ReviewInput().rating(5)));
        signIn(customer);
        var review = reviews.createReview(created.getId(), new ReviewInput().rating(5).text("Great"));
        failure(HttpStatus.CONFLICT, () -> reviews.createReview(created.getId(), new ReviewInput().rating(4)));
        signIn(owner);
        assertEquals("Thank you", dashboard.replyToReview(review.getId(), new ReplyToReviewRequest().text("Thank you")).getProviderReply());
        assertEquals(1, dashboard.listBusinessReviews(0, 20).getTotalElements());
        assertEquals(50000L, dashboard.getEarnings("2026-10").getTotal().getAmount());
        assertEquals(1, dashboard.getEarnings("2026-10").getDaily().size());
        assertEquals(1, reviews.listProviderReviews(provider, 0, 20).getTotalElements());
    }

    @Test
    void dashboardProfileAvailabilityOverviewAndScheduleRoundTrip() {
        bookings.createBooking(UUID.randomUUID(), booking(start));
        signIn(owner);
        assertEquals("Updated", dashboard.updateBusinessProfile(new BusinessProfileUpdate().businessName("Updated").note("Welcome")).getBusinessName());
        assertEquals("Welcome", dashboard.getBusinessProfile().getNote());
        var input = new AvailabilityInput().byAppointmentOnly(false).weekly(List.of(new AvailabilityInputWeeklyInner()
                .day(AvailabilityInputWeeklyInner.DayEnum.MON).open(true).from("10:00").to("17:00"))).blockedDates(List.of(LocalDate.of(2026, 12, 25)));
        assertEquals("10:00", dashboard.updateBusinessAvailability(input).getWeekly().getFirst().getFrom());
        assertEquals(1, dashboard.getBusinessAvailability().getBlockedDates().size());
        assertEquals(1, dashboard.getDashboardOverview().getAwaitingReply());
        assertEquals(1, dashboard.getDashboardSchedule(start.toLocalDate(), start.toLocalDate()).size());
        assertEquals("Owner", profiles.getProviderSocialProfile(provider).getDisplayName());
    }

    @Test
    void backendUploadsCompleteBeforeGalleryAttachmentAndStayOwned() {
        signIn(owner);
        var slot = media.createUpload(UploadPurpose.GALLERY_PHOTO, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[100]));
        assertEquals("UPLOADED", jdbc.queryForObject("SELECT status FROM pg_temp.uploads WHERE id = ?", String.class, slot.getUploadId()));
        var attached = dashboard.addOwnMedia(new AddOwnMediaRequest().uploadId(slot.getUploadId()).caption("My work"));
        verify(storage).upload(anyString(), eq("image/jpeg"), eq(100L), any(InputStream.class));
        verify(storage, never()).verify(anyString(), anyString(), anyLong());
        assertEquals("UPLOADED", jdbc.queryForObject("SELECT status FROM pg_temp.uploads WHERE id = ?", String.class, slot.getUploadId()));
        assertEquals(attached.getId(), dashboard.addOwnMedia(new AddOwnMediaRequest().uploadId(slot.getUploadId())).getId());
        assertEquals(1, dashboard.listOwnMedia(0, 20).getTotalElements());
        assertEquals(1, profiles.listProviderMedia(provider, "PHOTO", 0, 20).getTotalElements());
        signIn(other);
        failure(HttpStatus.BAD_REQUEST, () -> media.confirm(other, slot.getUploadId(), Set.of("GALLERY_PHOTO")));
        signIn(owner);
        dashboard.deleteOwnMedia(attached.getId());
        assertTrue(profiles.listProviderMedia(provider, null, 0, 20).getContent().isEmpty());
    }

    @Test
    void failedS3UploadRollsBackMetadata() {
        signIn(owner);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable"))
                .when(storage).upload(anyString(), anyString(), anyLong(), any(InputStream.class));
        failure(HttpStatus.SERVICE_UNAVAILABLE,
                () -> media.createUpload(UploadPurpose.AVATAR, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[100])));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_temp.uploads", Integer.class));
    }

}
