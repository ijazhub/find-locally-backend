package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.BookingsRepository;
import com.findlocally.api.repository.BookingsRepository.*;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingsServiceTests {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final BookingsRepository repository = mock(BookingsRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
    private final BookingsService service = new BookingsService(repository, currentUser,
            VALIDATORS.getValidator(), JsonMapper.builder().build(), clock, "Asia/Kolkata",
            mock(com.findlocally.api.repository.NotificationsRepository.class));
    private final UUID customer = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();

    BookingsServiceTests() {
        when(currentUser.id()).thenReturn(customer);
        when(repository.isCustomerAccount(customer)).thenReturn(true);
        when(repository.claimKey(eq(customer), eq(key), anyString(), anyString()))
                .thenAnswer(call -> new IdempotencyRecord(call.getArgument(2), call.getArgument(3), null));
        when(repository.findOffer(provider, serviceId)).thenReturn(
                new Offer(60, 50000L, "INR", List.of(Fulfilment.AT_STUDIO, Fulfilment.AT_CLIENT), false));
        when(repository.hours(provider, "MON")).thenReturn(new Hours(true, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        when(repository.insert(eq(customer), any(), any(), any(), any())).thenReturn(bookingId);
        when(repository.find(customer, bookingId, false)).thenReturn(booking());
    }

    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    private BookingCreate input() {
        return new BookingCreate().providerId(provider).serviceId(serviceId)
                .startsAt(OffsetDateTime.parse("2026-10-19T10:00:00+05:30")).fulfilment(Fulfilment.AT_STUDIO);
    }

    private Booking booking() {
        return new Booking().id(bookingId).status(BookingStatus.AWAITING_CONFIRMATION).startsAt(input().getStartsAt())
                .provider(new ProviderSummary().id(provider));
    }

    @Test
    void validRequestUsesDurationAndCompletesIdempotencyRecord() {
        var created = service.createBooking(key, input());
        assertEquals(bookingId, created.getId());
        verify(repository).insert(eq(customer), any(), any(), isNull(),
                eq(OffsetDateTime.parse("2026-10-19T11:00:00+05:30")));
        verify(repository).completeKey(customer, key, created);
    }

    @Test
    void rejectsInvalidInputBeforeClaimingKey() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(null, input()));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(key, new BookingCreate()));
        verify(repository, never()).claimKey(any(), any(), any(), any());
    }

    @Test
    void successfulRetryReplaysBeforeRecheckingPastTimeOrDeletedService() {
        var original = booking();
        when(repository.claimKey(eq(customer), eq(key), anyString(), anyString()))
                .thenAnswer(call -> new IdempotencyRecord(call.getArgument(2), call.getArgument(3), "saved"));
        when(repository.replay("saved")).thenReturn(original);
        var request = input().startsAt(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        assertSame(original, service.createBooking(key, request));
        verify(repository, never()).findOffer(any(), any());
        verify(repository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void changedRequestOrDifferentEndpointCannotReuseAKey() {
        when(repository.claimKey(eq(customer), eq(key), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord("/api/v1/cart/checkout", "other", "{}"));
        assertStatus(HttpStatus.CONFLICT, () -> service.createBooking(key, input()));
        verify(repository, never()).findOffer(any(), any());
    }

    @Test
    void omittedProposalPreservesTheLegacyIdempotencyFingerprint() {
        record Legacy(UUID provider,UUID service,String startsAt,Fulfilment fulfilment,UUID address,String note) {}
        var input=input();
        String original=com.findlocally.api.utility.BookingRequests.hash(new Legacy(provider,serviceId,input.getStartsAt().toInstant().toString(),input.getFulfilment(),null,null));
        service.createBooking(key,input);
        verify(repository).claimKey(customer,key,"/api/v1/bookings",original);
    }

    @Test
    void equivalentTimezoneOffsetsHaveTheSameRequestHash() {
        service.createBooking(key, input());
        service.createBooking(key, input().startsAt(input().getStartsAt().withOffsetSameInstant(ZoneOffset.UTC)));
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).claimKey(eq(customer), eq(key), anyString(), hashes.capture());
        assertEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1));
    }

    @Test
    void validatesTimeFulfilmentAndAddressOwnership() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(key,
                input().startsAt(OffsetDateTime.parse("2026-10-14T10:00:00Z"))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(key, input().fulfilment(Fulfilment.DELIVERY)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(key, input().fulfilment(Fulfilment.AT_CLIENT)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBooking(key, input().addressId(UUID.randomUUID())));
        verify(repository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void localMidnightEndDoesNotOccupyTheNextBlockedDate() {
        when(repository.findOffer(provider,serviceId)).thenReturn(new Offer(60,50000L,"INR",List.of(Fulfilment.AT_STUDIO),true));
        service.createBooking(key,input().startsAt(OffsetDateTime.parse("2026-10-19T17:30:00Z")));
        verify(repository).blocked(provider,LocalDate.of(2026,10,19),LocalDate.of(2026,10,19));
    }

    @Test
    void rejectsBlockedDatesAndOutsideHours() {
        assertStatus(HttpStatus.CONFLICT, () -> service.createBooking(key,
                input().startsAt(OffsetDateTime.parse("2026-10-19T17:30:00+05:30"))));
        when(repository.blocked(eq(provider), any(), any())).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.createBooking(key, input()));
        verify(repository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void translatesOnlyDatabaseOverlapViolationsToConflict() {
        when(repository.insert(any(), any(), any(), any(), any())).thenThrow(
                new DataIntegrityViolationException("slot", new SQLException("overlap", "23P01")));
        assertStatus(HttpStatus.CONFLICT, () -> service.createBooking(key, input()));
        doThrow(new DataIntegrityViolationException("other", new SQLException("foreign key", "23503")))
                .when(repository).insert(any(), any(), any(), any(), any());
        assertThrows(DataIntegrityViolationException.class, () -> service.createBooking(key, input()));
    }

    @Test
    void cancellationIsOwnerScopedLockedAndRetryable() {
        var booking = booking();
        when(repository.find(customer, bookingId, true)).thenReturn(booking);
        when(repository.find(customer, bookingId, false)).thenReturn(booking);
        assertEquals(BookingStatus.CANCELLED, service.cancelBooking(bookingId, null).getStatus());
        assertEquals(BookingStatus.CANCELLED, service.cancelBooking(bookingId, null).getStatus());
        verify(repository, times(1)).cancel(customer, bookingId, null);
    }

    @Test
    void cannotCancelCompletedStartedOrForeignBookings() {
        when(repository.find(customer, bookingId, true)).thenReturn(booking().status(BookingStatus.COMPLETED));
        assertStatus(HttpStatus.CONFLICT, () -> service.cancelBooking(bookingId, null));
        when(repository.find(customer, bookingId, true)).thenReturn(booking().startsAt(OffsetDateTime.now(clock)));
        assertStatus(HttpStatus.CONFLICT, () -> service.cancelBooking(bookingId, null));
        when(repository.find(customer, bookingId, true)).thenReturn(null);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.cancelBooking(bookingId, null));
        verify(repository, never()).cancel(any(), any(), any());
    }

    @Test
    void paginationUsesContractDefaultsAndRejectsInvalidBounds() {
        when(repository.count(customer, "UPCOMING")).thenReturn(41L);
        when(repository.list(customer, "UPCOMING", 0, 20)).thenReturn(List.of());
        var page = service.listMyBookings(null, null, null);
        assertEquals(3, page.getTotalPages());
        assertEquals(41L, page.getTotalElements());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.listMyBookings("INVALID", 0, 20));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.listMyBookings("DONE", -1, 20));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.listMyBookings("DONE", 0, 51));
    }

    private void assertStatus(HttpStatus status, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(status, assertThrows(ResponseStatusException.class, operation).getStatusCode());
    }
}
