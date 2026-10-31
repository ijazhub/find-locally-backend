package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NegotiationService {
    private final NegotiationRepository repository;
    private final BookingsRepository bookings;
    private final BusinessAccess access;
    private final CurrentUser current;
    private final Inputs inputs;
    private final Clock clock;
    private final Duration quoteWindow;
    public NegotiationService(NegotiationRepository repository, BookingsRepository bookings, BusinessAccess access,
            CurrentUser current, Inputs inputs, Clock clock,
            @Value("${app.bookings.quotation-response-hours:12}") long hours) {
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("Invalid quotation response window");
        this.repository=repository; this.bookings=bookings; this.access=access; this.current=current;
        this.inputs=inputs; this.clock=clock; this.quoteWindow=Duration.ofHours(hours);
    }
    @Transactional
    public Booking issue(UUID id, UUID key, QuotationInput input) {
        inputs.valid(input);
        UUID business=access.lock(), user=current.id();
        String path="/api/v1/business/bookings/"+id+"/quotations";
        Booking replay=claim(user,key,path,input);
        if (replay!=null) return replay;
        var booking=bookings.findBusiness(business,id,true);
        if (booking==null) throw Inputs.missing("Booking");
        requirePending(booking);
        validateMoney(input.getPrice(),booking.getTotal().getCurrency());
        OffsetDateTime now=OffsetDateTime.now(clock), deadline=now.plus(quoteWindow);
        if (deadline.isAfter(booking.getHoldExpiresAt())) deadline=booking.getHoldExpiresAt();
        if (!deadline.isAfter(now)) throw Inputs.conflict("Booking response deadline expired");
        repository.actor(user);
        repository.issue(id,input.getPrice(),now,deadline);
        var result=bookings.findBusiness(business,id,false);
        bookings.completeKey(user,key,result,200);
        return result;
    }
    @Transactional
    public Booking respond(UUID id, UUID key, QuotationResponse input) {
        inputs.valid(input);
        UUID user=current.id();
        String path="/api/v1/bookings/"+id+"/quotation-response";
        Booking replay=claim(user,key,path,input);
        if (replay!=null) return replay;
        var booking=bookings.find(user,id,true);
        if (booking==null) throw Inputs.missing("Booking");
        requirePending(booking);
        if (booking.getStatus()!=BookingStatus.AWAITING_CUSTOMER_APPROVAL) throw Inputs.conflict("No quotation awaits approval");
        var quote=repository.quotations(id).stream().filter(q -> q.getState()==Quotation.StateEnum.CURRENT).findFirst().orElseThrow(() -> Inputs.conflict("No current quotation"));
        if (!quote.getId().equals(input.getQuotationId()) || !quote.getVersion().equals(input.getVersion()))
            throw Inputs.conflict("Stale quotation: reload the current quotation before responding");
        if (!quote.getExpiresAt().toInstant().isAfter(clock.instant())) throw Inputs.conflict("Quotation expired");
        boolean accept=input.getDecision()==QuotationResponse.DecisionEnum.ACCEPT;
        if (!accept) inputs.text(input.getReason(),500);
        repository.actor(user);
        repository.respond(id,quote.getId(),accept,accept ? null : input.getReason());
        var result=bookings.find(user,id,false);
        bookings.completeKey(user,key,result,200);
        return result;
    }
    private Booking claim(UUID user, UUID key, String path, Object input) {
        if (key==null) throw Inputs.bad("Idempotency-Key is required");
        String hash=BookingRequests.hash(input);
        var record=bookings.claimKey(user,key,path,hash);
        if (!path.equals(record.path()) || !hash.equals(record.requestHash())) throw Inputs.conflict("Idempotency key was used for another request");
        return record.responseBody()==null ? null : bookings.replay(record.responseBody());
    }
    private void requirePending(Booking booking) {
        if (!List.of(BookingStatus.AWAITING_CONFIRMATION,BookingStatus.AWAITING_CUSTOMER_APPROVAL).contains(booking.getStatus()))
            throw Inputs.conflict("Booking does not await a response");
        if (!booking.getResponseDeadline().toInstant().isAfter(clock.instant())) throw Inputs.conflict("Booking response deadline expired");
    }
    public static void validateMoney(Money price, String currency) {
        if (price==null || price.getAmount()==null || price.getAmount()<0 || !currency.equals(price.getCurrency()))
            throw Inputs.bad("Price must be nonnegative minor units in the service currency");
    }
}
