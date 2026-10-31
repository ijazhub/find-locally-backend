package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class BusinessBookingsService {
    private final BusinessBookingsRepository repository;
    private final BookingsRepository bookings;
    private final NotificationsRepository notifications;
    private final BusinessAccess access;
    private final Inputs inputs;
    private final Clock clock;
    private final ZoneId zone;
    private final CurrentUser current;
    public BusinessBookingsService(BusinessBookingsRepository repository, BookingsRepository bookings, NotificationsRepository notifications,
            BusinessAccess access, Inputs inputs, Clock clock, CurrentUser current, @Value("${app.bookings.time-zone:Asia/Kolkata}") String zone) {
        this.repository = repository; this.bookings = bookings; this.notifications = notifications;
        this.access = access; this.inputs = inputs; this.clock = clock; this.current=current; this.zone = ZoneId.of(zone);
    }
    @Transactional public Booking acceptBooking(UUID id) { return change(id, BookingStatus.CONFIRMED, null); }
    @Transactional public Booking completeBooking(UUID id) { return change(id, BookingStatus.COMPLETED, null); }
    @Transactional public Booking declineBooking(UUID id, CancelBookingRequest request) {
        if (request != null) inputs.valid(request);
        inputs.text(request == null ? null : request.getReason(), 500);
        return change(id, BookingStatus.DECLINED, request.getReason());
    }
    @Transactional(readOnly = true)
    public BookingPage listBusinessBookings(BookingStatus status, LocalDate from, LocalDate to, Integer page, Integer size) {
        if (from != null && to != null && to.isBefore(from)) throw Inputs.bad("to must be on or after from");
        return bookings.businessPage(access.id(), status, from == null ? null : from.atStartOfDay(zone).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), Pages.of(page, size));
    }
    private Booking change(UUID id, BookingStatus target, String reason) {
        UUID business = access.lock();
        var booking = bookings.findBusiness(business, id, true);
        if (booking == null) throw Inputs.missing("Booking");
        if (booking.getStatus() == target) return booking;
        if (target == BookingStatus.COMPLETED) {
            if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getEndsAt().toInstant().isAfter(clock.instant()))
                throw Inputs.conflict("Only a confirmed booking that has finished can be completed");
        } else if ((target==BookingStatus.CONFIRMED ? booking.getStatus()!=BookingStatus.AWAITING_CONFIRMATION
                : !java.util.List.of(BookingStatus.AWAITING_CONFIRMATION,BookingStatus.AWAITING_CUSTOMER_APPROVAL).contains(booking.getStatus()))
                || !booking.getStartsAt().toInstant().isAfter(clock.instant())) {
            throw Inputs.conflict("Only a future booking awaiting confirmation can be accepted or declined");
        }
        if (target!=BookingStatus.COMPLETED && booking.getResponseDeadline()!=null && !booking.getResponseDeadline().toInstant().isAfter(clock.instant()))
            throw Inputs.conflict("Booking response deadline expired");
        bookings.actor(current.id());
        repository.transition(business, id, target.getValue(), reason);
        booking.setStatus(target);
        String type = target == BookingStatus.CONFIRMED ? "BOOKING_CONFIRMED" : target == BookingStatus.DECLINED ? "BOOKING_DECLINED" : "SYSTEM";
        notifications.notify(booking.getCustomer().getId(), type, "Booking updated", "Your booking is now " + target.getValue() + ".", "/bookings/" + id);
        return bookings.findBusiness(business,id,false);
    }
}

