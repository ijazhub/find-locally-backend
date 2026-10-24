package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class ReviewsService {
    private final ReviewsRepository repository;
    private final BookingsRepository bookings;
    private final CatalogRepository catalog;
    private final NotificationsRepository notifications;
    private final CurrentUser current;
    private final Inputs inputs;
    public ReviewsService(ReviewsRepository repository, BookingsRepository bookings, CatalogRepository catalog,
            NotificationsRepository notifications, CurrentUser current, Inputs inputs) {
        this.repository = repository; this.bookings = bookings; this.catalog = catalog;
        this.notifications = notifications; this.current = current; this.inputs = inputs;
    }
    @Transactional
    public Review createReview(UUID bookingId, ReviewInput input) {
        UUID user = current.id();
        inputs.valid(input);
        var booking = bookings.find(user, bookingId, true);
        if (booking == null) throw Inputs.missing("Booking");
        if (booking.getStatus() != BookingStatus.COMPLETED) throw Inputs.conflict("Only completed bookings can be reviewed");
        if (repository.exists(bookingId)) throw Inputs.conflict("This booking already has a review");
        UUID provider = booking.getProvider().getId();
        var review = repository.create(user, bookingId, provider, input);
        notifications.notify(notifications.businessOwner(provider), "REVIEW", "You received a review",
                "A customer reviewed a completed booking.", "/business/reviews");
        return review;
    }
    @Transactional(readOnly = true)
    public ReviewPage listProviderReviews(UUID providerId, Integer page, Integer size) {
        if (catalog.provider(providerId, null) == null) throw Inputs.missing("Provider");
        return repository.page(providerId, Pages.of(page, size));
    }
}

