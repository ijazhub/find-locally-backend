package com.findlocally.api.service;

import com.findlocally.api.model.Booking;
import com.findlocally.api.model.BookingCreate;
import com.findlocally.api.model.BookingPage;
import com.findlocally.api.model.BookingStatus;
import com.findlocally.api.model.BookingSummary;
import com.findlocally.api.model.CancelBookingRequest;
import com.findlocally.api.model.Fulfilment;
import com.findlocally.api.repository.BookingsRepository;
import com.findlocally.api.repository.NotificationsRepository;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingsService {
    private static final String BOOKING_PATH = "/api/v1/bookings";
    private final BookingsRepository repository;
    private final CurrentUser currentUser;
    private final Validator validator;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ZoneId businessZone;
    private final NotificationsRepository notifications;

    public BookingsService(BookingsRepository repository, CurrentUser currentUser, Validator validator,
            ObjectMapper mapper, Clock clock, @Value("${app.bookings.time-zone:Asia/Kolkata}") String timeZone,
            NotificationsRepository notifications) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
        this.businessZone = ZoneId.of(timeZone);
        this.notifications = notifications;
    }

    @Transactional
    public Booking createBooking(UUID idempotencyKey, BookingCreate input) {
        UUID customer = currentUser.id();
        if (idempotencyKey == null || input == null || !validator.validate(input).isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "Idempotency-Key and valid booking details are required");
        }
        if (!repository.isCustomerAccount(customer)) throw error(HttpStatus.FORBIDDEN, "A CUSTOMER account is required");
        String hash = requestHash(input);
        var key = repository.claimKey(customer, idempotencyKey, BOOKING_PATH, hash);
        if (!BOOKING_PATH.equals(key.path()) || !hash.equals(key.requestHash())) {
            throw error(HttpStatus.CONFLICT, "Idempotency key was already used for a different request");
        }
        // Replay before checking time, availability or service state: a successful retry is immutable.
        if (key.responseBody() != null) return repository.replay(key.responseBody());
        if (!input.getStartsAt().toInstant().isAfter(clock.instant())) {
            throw error(HttpStatus.BAD_REQUEST, "Booking start must be in the future");
        }
        var offer = repository.findOffer(input.getProviderId(), input.getServiceId());
        repository.releaseExpired(input.getProviderId(), OffsetDateTime.now(clock));
        if (input.getProposedPrice()!=null && offer!=null) NegotiationService.validateMoney(input.getProposedPrice(),offer.currency());
        if (offer == null) throw error(HttpStatus.NOT_FOUND, "Active provider service not found");
        if (!offer.fulfilment().contains(input.getFulfilment())) {
            throw error(HttpStatus.BAD_REQUEST, "The service does not support this fulfilment");
        }
        var address = input.getAddressId() == null ? null : repository.findAddress(customer, input.getAddressId());
        if ((input.getAddressId() != null && address == null)
                || (List.of(Fulfilment.AT_CLIENT, Fulfilment.DELIVERY).contains(input.getFulfilment()) && address == null)) {
            throw error(HttpStatus.BAD_REQUEST, "Choose an address belonging to your account");
        }
        OffsetDateTime endsAt;
        try {
            endsAt = input.getStartsAt().plusMinutes(offer.durationMinutes());
        } catch (java.time.DateTimeException exception) {
            throw error(HttpStatus.BAD_REQUEST, "Invalid booking time");
        }
        checkAvailability(input, offer, endsAt);
        UUID bookingId;
        try {
            repository.actor(customer);
            bookingId = repository.insert(customer, input, offer, address, endsAt);
        } catch (DataIntegrityViolationException exception) {
            if (isOverlappingBooking(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This time slot is already booked", exception);
            }
            throw exception;
        }
        Booking booking = repository.find(customer, bookingId, false);
        notifications.notify(notifications.businessOwner(input.getProviderId()), "BOOKING_REQUESTED",
                "New booking request", "A customer requested a booking.", "/business/bookings");
        repository.completeKey(customer, idempotencyKey, booking);
        return booking;
    }

    @Transactional
    public Booking cancelBooking(UUID bookingId, CancelBookingRequest request) {
        UUID customer = currentUser.id();
        if (request != null && !validator.validate(request).isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "Cancellation reason must be at most 500 characters");
        }
        Booking booking = requireBooking(customer, bookingId, true);
        if (booking.getStatus() == BookingStatus.CANCELLED) return booking;
        if (booking.getStatus()!=BookingStatus.CONFIRMED && booking.getResponseDeadline()!=null
                && !booking.getResponseDeadline().toInstant().isAfter(clock.instant()))
            throw error(HttpStatus.CONFLICT,"Booking response deadline expired");
        if (!List.of(BookingStatus.AWAITING_CONFIRMATION, BookingStatus.AWAITING_CUSTOMER_APPROVAL, BookingStatus.CONFIRMED).contains(booking.getStatus())
                || !booking.getStartsAt().toInstant().isAfter(clock.instant())) {
            throw error(HttpStatus.CONFLICT, "Only a pending or confirmed booking that has not started can be cancelled");
        }
        repository.actor(customer);
        repository.cancel(customer, bookingId, request == null ? null : request.getReason());
        booking.setStatus(BookingStatus.CANCELLED);
        notifications.notify(notifications.businessOwner(booking.getProvider().getId()), "BOOKING_CANCELLED",
                "Booking cancelled", "A customer cancelled a booking.", "/business/bookings");
        return repository.find(customer,bookingId,false);
    }

    @Transactional(readOnly = true)
    public Booking getBooking(UUID bookingId) {
        var booking=repository.findParticipant(currentUser.id(),bookingId);
        if(booking==null) throw error(HttpStatus.NOT_FOUND,"Booking not found");
        return booking;
    }

    @Transactional(readOnly = true)
    public BookingSummary getBookingSummary() {
        var summary = repository.summary(currentUser.id(), OffsetDateTime.now(clock));
        if (summary.getNextBookingAt() != null) {
            String date = summary.getNextBookingAt().atZoneSameInstant(businessZone)
                    .format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH));
            summary.setNextBookingLabel("Your next booking is on " + date + ".");
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public BookingPage listMyBookings(String tab, Integer page, Integer size) {
        UUID customer = currentUser.id();
        String selectedTab = tab == null ? "UPCOMING" : tab;
        int selectedPage = page == null ? 0 : page;
        int selectedSize = size == null ? 20 : size;
        if (!List.of("UPCOMING", "DONE", "CANCELLED").contains(selectedTab)
                || selectedPage < 0 || selectedSize < 1 || selectedSize > 50) {
            throw error(HttpStatus.BAD_REQUEST, "Use a valid tab, a non-negative page and size between 1 and 50");
        }
        long total = repository.count(customer, selectedTab);
        return new BookingPage().page(selectedPage).size(selectedSize).totalElements(total)
                .totalPages(Math.toIntExact((total + selectedSize - 1) / selectedSize))
                .content(repository.list(customer, selectedTab, selectedPage, selectedSize));
    }

    private void checkAvailability(BookingCreate input, BookingsRepository.Offer offer, OffsetDateTime endsAt) {
        var start = input.getStartsAt().atZoneSameInstant(businessZone);
        var end = endsAt.atZoneSameInstant(businessZone);
        // The interval is [start, end); ending at midnight does not occupy the following date.
        if (repository.blocked(input.getProviderId(), start.toLocalDate(), end.minusNanos(1).toLocalDate())) {
            throw error(HttpStatus.CONFLICT, "The provider is unavailable on this date");
        }
        if (offer.byAppointmentOnly()) return;
        var hours = repository.hours(input.getProviderId(), start.getDayOfWeek().name().substring(0, 3));
        if (hours == null || !hours.open() || hours.from() == null || hours.to() == null
                || !start.toLocalDate().equals(end.toLocalDate())
                || start.toLocalTime().isBefore(hours.from()) || end.toLocalTime().isAfter(hours.to())) {
            throw error(HttpStatus.CONFLICT, "The booking falls outside the provider's opening hours");
        }
    }

    private Booking requireBooking(UUID customer, UUID bookingId, boolean lock) {
        if (bookingId == null) throw error(HttpStatus.BAD_REQUEST, "bookingId is required");
        Booking booking = repository.find(customer, bookingId, lock);
        if (booking == null) throw error(HttpStatus.NOT_FOUND, "Booking not found");
        return booking;
    }

    private String requestHash(BookingCreate input) {
        // Normalize timezone offsets so equivalent instants produce the same idempotency fingerprint.
        record Payload(UUID provider, UUID service, String startsAt, Fulfilment fulfilment, UUID address, String note, com.findlocally.api.model.Money proposedPrice) {}
        // Preserve pre-negotiation fingerprints when the optional proposal is omitted, including cart checkout.
        record LegacyPayload(UUID provider, UUID service, String startsAt, Fulfilment fulfilment, UUID address, String note) {}
        Object payload=input.getProposedPrice()==null
                ? new LegacyPayload(input.getProviderId(),input.getServiceId(),input.getStartsAt().toInstant().toString(),input.getFulfilment(),input.getAddressId(),input.getNote())
                : new Payload(input.getProviderId(),input.getServiceId(),input.getStartsAt().toInstant().toString(),input.getFulfilment(),input.getAddressId(),input.getNote(),input.getProposedPrice());
        String json = mapper.writeValueAsString(payload);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isOverlappingBooking(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}

