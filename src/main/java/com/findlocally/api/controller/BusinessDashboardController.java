package com.findlocally.api.controller;

import com.findlocally.api.contract.BusinessDashboardApi;
import com.findlocally.api.service.BusinessDashboardService;
import com.findlocally.api.model.AddOwnMediaRequest;
import com.findlocally.api.model.AvailabilityInput;
import com.findlocally.api.model.Booking;
import com.findlocally.api.model.BusinessProfile;
import com.findlocally.api.model.BusinessProfileUpdate;
import com.findlocally.api.model.DashboardOverview;
import com.findlocally.api.model.Earnings;
import java.time.LocalDate;
import com.findlocally.api.model.MediaItem;
import com.findlocally.api.model.MediaPage;
import com.findlocally.api.model.ReplyToReviewRequest;
import com.findlocally.api.model.Review;
import com.findlocally.api.model.ReviewPage;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusinessDashboardController implements BusinessDashboardApi {
    private final BusinessDashboardService service;

    public BusinessDashboardController(BusinessDashboardService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<MediaItem> updateOwnMedia(UUID mediaId,com.findlocally.api.model.MediaUpdate input) {
        return ResponseEntity.ok(service.updateOwnMedia(mediaId,input));
    }
    @Override
    public ResponseEntity<MediaItem> addOwnMedia(AddOwnMediaRequest addOwnMediaRequest) {
        return ResponseEntity.status(201).body(service.addOwnMedia(addOwnMediaRequest));
    }

    @Override
    public ResponseEntity<Void> deleteOwnMedia(UUID mediaId) {
        service.deleteOwnMedia(mediaId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<AvailabilityInput> getBusinessAvailability() {
        return ResponseEntity.status(200).body(service.getBusinessAvailability());
    }

    @Override
    public ResponseEntity<BusinessProfile> getBusinessProfile() {
        return ResponseEntity.status(200).body(service.getBusinessProfile());
    }

    @Override
    public ResponseEntity<DashboardOverview> getDashboardOverview() {
        return ResponseEntity.status(200).body(service.getDashboardOverview());
    }

    @Override
    public ResponseEntity<List<Booking>> getDashboardSchedule(LocalDate from, LocalDate to) {
        return ResponseEntity.status(200).body(service.getDashboardSchedule(from, to));
    }

    @Override
    public ResponseEntity<Earnings> getEarnings(String month) {
        return ResponseEntity.status(200).body(service.getEarnings(month));
    }

    @Override
    public ResponseEntity<ReviewPage> listBusinessReviews(Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listBusinessReviews(page, size));
    }

    @Override
    public ResponseEntity<MediaPage> listOwnMedia(Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listOwnMedia(page, size));
    }

    @Override
    public ResponseEntity<Review> replyToReview(UUID reviewId, ReplyToReviewRequest replyToReviewRequest) {
        return ResponseEntity.status(200).body(service.replyToReview(reviewId, replyToReviewRequest));
    }

    @Override
    public ResponseEntity<AvailabilityInput> updateBusinessAvailability(AvailabilityInput availabilityInput) {
        return ResponseEntity.status(200).body(service.updateBusinessAvailability(availabilityInput));
    }

    @Override
    public ResponseEntity<BusinessProfile> updateBusinessProfile(BusinessProfileUpdate businessProfileUpdate) {
        return ResponseEntity.status(200).body(service.updateBusinessProfile(businessProfileUpdate));
    }
}

