package com.findlocally.api.controller;

import com.findlocally.api.contract.BusinessBookingsApi;
import com.findlocally.api.service.BusinessBookingsService;
import com.findlocally.api.model.Booking;
import com.findlocally.api.model.BookingPage;
import com.findlocally.api.model.BookingStatus;
import com.findlocally.api.model.CancelBookingRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusinessBookingsController implements BusinessBookingsApi {
    private final BusinessBookingsService service;
    private final com.findlocally.api.service.NegotiationService negotiation;

    public BusinessBookingsController(BusinessBookingsService service, com.findlocally.api.service.NegotiationService negotiation) {
        this.service = service; this.negotiation = negotiation;
    }

    @Override
    public ResponseEntity<Booking> issueQuotation(UUID bookingId, UUID idempotencyKey, com.findlocally.api.model.QuotationInput input) {
        return ResponseEntity.ok(negotiation.issue(bookingId,idempotencyKey,input));
    }

    @Override
    public ResponseEntity<Booking> acceptBooking(UUID bookingId) {
        return ResponseEntity.status(200).body(service.acceptBooking(bookingId));
    }

    @Override
    public ResponseEntity<Booking> completeBooking(UUID bookingId) {
        return ResponseEntity.status(200).body(service.completeBooking(bookingId));
    }

    @Override
    public ResponseEntity<Booking> declineBooking(UUID bookingId, com.findlocally.api.model.DeclineBookingRequest request) {
        return ResponseEntity.status(200).body(service.declineBooking(bookingId, new CancelBookingRequest().reason(request.getReason())));
    }

    @Override
    public ResponseEntity<BookingPage> listBusinessBookings(BookingStatus status, LocalDate from, LocalDate to, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listBusinessBookings(status, from, to, page, size));
    }
}

