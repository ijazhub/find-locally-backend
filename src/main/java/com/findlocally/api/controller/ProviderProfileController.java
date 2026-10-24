package com.findlocally.api.controller;

import com.findlocally.api.contract.ProviderProfileApi;
import com.findlocally.api.service.ProviderProfileService;
import com.findlocally.api.model.MediaPage;
import com.findlocally.api.model.ProviderSocialProfile;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProviderProfileController implements ProviderProfileApi {
    private final ProviderProfileService service;

    public ProviderProfileController(ProviderProfileService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ProviderSocialProfile> getProviderSocialProfile(UUID providerId) {
        return ResponseEntity.status(200).body(service.getProviderSocialProfile(providerId));
    }

    @Override
    public ResponseEntity<MediaPage> listProviderMedia(UUID providerId, String type, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listProviderMedia(providerId, type, page, size));
    }
}

