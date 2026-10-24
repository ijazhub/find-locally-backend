package com.findlocally.api.controller;

import com.findlocally.api.contract.ReviewsApi;
import com.findlocally.api.service.ReviewsService;
import com.findlocally.api.model.Review;
import com.findlocally.api.model.ReviewInput;
import com.findlocally.api.model.ReviewPage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReviewsController implements ReviewsApi {
    private final ReviewsService service;

    public ReviewsController(ReviewsService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Review> createReview(UUID bookingId, ReviewInput reviewInput) {
        return ResponseEntity.status(201).body(service.createReview(bookingId, reviewInput));
    }

    @Override
    public ResponseEntity<ReviewPage> listProviderReviews(UUID providerId, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listProviderReviews(providerId, page, size));
    }
}

