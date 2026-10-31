package com.findlocally.api.utility;

import com.findlocally.api.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookingActionsTests {
    @Test void actionsDependOnParticipantCurrentStateAndDeadline() {
        UUID customer=UUID.randomUUID(),owner=UUID.randomUUID();Instant now=Instant.parse("2026-11-01T00:00:00Z");
        var b=new Booking().customer(new BookingCustomer().id(customer)).status(BookingStatus.AWAITING_CUSTOMER_APPROVAL)
                .startsAt(OffsetDateTime.parse("2026-11-03T00:00:00Z")).endsAt(OffsetDateTime.parse("2026-11-03T01:00:00Z"))
                .responseDeadline(OffsetDateTime.parse("2026-11-02T00:00:00Z"));
        assertEquals(List.of("CANCEL","ACCEPT_QUOTATION","REJECT_QUOTATION"),BookingActions.allowed(b,customer,owner,now));
        assertEquals(List.of("ISSUE_QUOTATION","DECLINE"),BookingActions.allowed(b,owner,owner,now));
        assertTrue(BookingActions.allowed(b,UUID.randomUUID(),owner,now).isEmpty());
        assertTrue(BookingActions.allowed(b,customer,owner,b.getResponseDeadline().toInstant()).isEmpty());
        b.status(BookingStatus.CONFIRMED);
        assertEquals(List.of("COMPLETE"),BookingActions.allowed(b,owner,owner,b.getEndsAt().toInstant()));
        b.status(BookingStatus.COMPLETED);assertTrue(BookingActions.allowed(b,owner,owner,now).isEmpty());
    }
}
