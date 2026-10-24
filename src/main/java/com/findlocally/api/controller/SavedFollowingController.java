package com.findlocally.api.controller;

import com.findlocally.api.contract.SavedFollowingApi;
import com.findlocally.api.service.SavedFollowingService;
import com.findlocally.api.model.ProviderPage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SavedFollowingController implements SavedFollowingApi {
    private final SavedFollowingService service;

    public SavedFollowingController(SavedFollowingService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Void> followProvider(UUID providerId) {
        service.followProvider(providerId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<ProviderPage> listFollowing(Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listFollowing(page, size));
    }

    @Override
    public ResponseEntity<ProviderPage> listSaved(String category, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listSaved(category, page, size));
    }

    @Override
    public ResponseEntity<Void> saveProvider(UUID providerId) {
        service.saveProvider(providerId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<Void> unfollowProvider(UUID providerId) {
        service.unfollowProvider(providerId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<Void> unsaveProvider(UUID providerId) {
        service.unsaveProvider(providerId);
        return ResponseEntity.status(204).build();
    }
}

