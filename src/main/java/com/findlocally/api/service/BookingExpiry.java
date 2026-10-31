package com.findlocally.api.service;

import com.findlocally.api.repository.NegotiationRepository;
import java.time.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingExpiry {
    private final NegotiationRepository repository;
    private final Clock clock;
    public BookingExpiry(NegotiationRepository repository, Clock clock) { this.repository=repository; this.clock=clock; }
    @Scheduled(initialDelayString="${app.bookings.expiry-delay-ms:30000}", fixedDelayString="${app.bookings.expiry-delay-ms:30000}")
    @Transactional
    public void expire() { repository.expire(OffsetDateTime.now(clock),null); }
}
