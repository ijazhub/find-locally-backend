package com.findlocally.api.controller;

import com.findlocally.api.contract.BookingsApi;
import com.findlocally.api.service.BookingsService;
import com.findlocally.api.model.Booking;
import com.findlocally.api.model.BookingCreate;
import com.findlocally.api.model.BookingPage;
import com.findlocally.api.model.BookingSummary;
import com.findlocally.api.model.CancelBookingRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BookingsController implements BookingsApi {
    private final BookingsService service;
    private final com.findlocally.api.service.NegotiationService negotiation;

    public BookingsController(BookingsService service, com.findlocally.api.service.NegotiationService negotiation) {
        this.service = service; this.negotiation = negotiation;
    }

    @Override
    public ResponseEntity<Booking> respondToQuotation(UUID bookingId, UUID idempotencyKey, com.findlocally.api.model.QuotationResponse input) {
        return ResponseEntity.ok(negotiation.respond(bookingId,idempotencyKey,input));
    }

    @Override
    public ResponseEntity<Booking> cancelBooking(UUID bookingId, CancelBookingRequest cancelBookingRequest) {
        return ResponseEntity.status(200).body(service.cancelBooking(bookingId, cancelBookingRequest));
    }

    @Override
    public ResponseEntity<Booking> createBooking(UUID idempotencyKey, BookingCreate bookingCreate) {
        return ResponseEntity.status(201).body(service.createBooking(idempotencyKey, bookingCreate));
    }

    @Override
    public ResponseEntity<Booking> getBooking(UUID bookingId) {
        return ResponseEntity.status(200).body(service.getBooking(bookingId));
    }

    @Override
    public ResponseEntity<BookingSummary> getBookingSummary() {
        return ResponseEntity.status(200).body(service.getBookingSummary());
    }

    @Override
    public ResponseEntity<BookingPage> listMyBookings(String tab, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listMyBookings(tab, page, size));
    }
}

