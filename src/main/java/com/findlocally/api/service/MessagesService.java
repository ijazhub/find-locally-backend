package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class MessagesService {
    private final MessagesRepository repository;
    private final CatalogRepository catalog;
    private final BookingsRepository bookings;
    private final NotificationsRepository notifications;
    private final CurrentUser current;
    private final Inputs inputs;
    public MessagesService(MessagesRepository repository, CatalogRepository catalog, BookingsRepository bookings,
            NotificationsRepository notifications, CurrentUser current, Inputs inputs) {
        this.repository = repository; this.catalog = catalog; this.bookings = bookings;
        this.notifications = notifications; this.current = current; this.inputs = inputs;
    }
    @Transactional(readOnly = true) public ConversationPage listConversations(Integer page, Integer size) {
        return repository.page(current.id(), Pages.of(page, size));
    }
    @Transactional(readOnly = true) public List<Message> listMessages(UUID id, OffsetDateTime before, Integer size) {
        require(id, false);
        return repository.messages(id, before, Pages.of(0, size).size());
    }
    @Transactional public void markConversationRead(UUID id) {
        require(id, true); repository.read(id, current.id());
    }
    @Transactional public Message sendMessage(UUID id, SendMessageRequest input) {
        inputs.valid(input); inputs.text(input.getText(), 2000);
        var participant = require(id, true);
        UUID user = current.id();
        var message = repository.send(id, user, input.getText());
        UUID recipient = user.equals(participant.customer()) ? participant.owner() : participant.customer();
        notifications.notify(recipient, "MESSAGE", "New message", "You have a new message.", "/conversations/" + id);
        return message;
    }
    @Transactional public Conversation startConversation(StartConversationRequest input) {
        UUID user = current.id(); inputs.valid(input);
        if (!bookings.isCustomerAccount(user)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"A CUSTOMER account is required to initiate chat");
        if (catalog.provider(input.getProviderId(), user) == null) throw Inputs.missing("Provider");
        UUID owner = notifications.businessOwner(input.getProviderId());
        if (user.equals(owner)) throw Inputs.bad("You cannot start a conversation with your own business");
        if (input.getBookingId() != null) {
            var booking = bookings.find(user, input.getBookingId(), false);
            if (booking == null || !booking.getProvider().getId().equals(input.getProviderId())) throw Inputs.missing("Booking");
        }
        return repository.conversation(user, repository.start(user, input.getProviderId(), input.getBookingId()));
    }
    private MessagesRepository.Participant require(UUID id, boolean lock) {
        var participant = repository.participant(current.id(), id, lock);
        if (participant == null) throw Inputs.missing("Conversation");
        return participant;
    }
}

