package com.findlocally.api.utility;

import com.findlocally.api.model.*;
import java.time.Instant;
import java.util.*;

public final class BookingActions {
    private BookingActions() {}
    public static List<String> allowed(Booking b, UUID viewer, UUID owner, Instant now) {
        if (viewer==null) return List.of();
        boolean customer=b.getCustomer().getId().equals(viewer), provider=viewer.equals(owner);
        if (!customer && !provider) return List.of();
        var actions=new ArrayList<String>();
        boolean future=b.getStartsAt().toInstant().isAfter(now);
        boolean pending=List.of(BookingStatus.AWAITING_CONFIRMATION,BookingStatus.AWAITING_CUSTOMER_APPROVAL).contains(b.getStatus());
        boolean live=pending && b.getResponseDeadline()!=null && b.getResponseDeadline().toInstant().isAfter(now);
        if (customer && future && (live || b.getStatus()==BookingStatus.CONFIRMED)) actions.add("CANCEL");
        if (customer && live && b.getStatus()==BookingStatus.AWAITING_CUSTOMER_APPROVAL) actions.addAll(List.of("ACCEPT_QUOTATION","REJECT_QUOTATION"));
        if (provider && live) {
            if (b.getStatus()==BookingStatus.AWAITING_CONFIRMATION) actions.add("ACCEPT_PROPOSAL");
            actions.addAll(List.of("ISSUE_QUOTATION","DECLINE"));
        }
        if (provider && b.getStatus()==BookingStatus.CONFIRMED && !b.getEndsAt().toInstant().isAfter(now)) actions.add("COMPLETE");
        return actions;
    }
}
