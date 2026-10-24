package com.findlocally.api.controller;

import com.findlocally.api.contract.BusinessOnboardingApi;
import com.findlocally.api.service.BusinessOnboardingService;
import com.findlocally.api.model.OnboardingState;
import com.findlocally.api.model.ProviderDetail;
import com.findlocally.api.model.SaveOnboardingStepRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusinessOnboardingController implements BusinessOnboardingApi {
    private final BusinessOnboardingService service;

    public BusinessOnboardingController(BusinessOnboardingService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<OnboardingState> getOnboarding() {
        return ResponseEntity.status(200).body(service.getOnboarding());
    }

    @Override
    public ResponseEntity<ProviderDetail> getOnboardingPreview() {
        return ResponseEntity.status(200).body(service.getOnboardingPreview());
    }

    @Override
    public ResponseEntity<OnboardingState> saveOnboardingStep(String step, SaveOnboardingStepRequest saveOnboardingStepRequest) {
        return ResponseEntity.status(200).body(service.saveOnboardingStep(step, saveOnboardingStepRequest));
    }

    @Override
    public ResponseEntity<OnboardingState> startOnboarding() {
        return ResponseEntity.status(201).body(service.startOnboarding());
    }

    @Override
    public ResponseEntity<OnboardingState> submitOnboarding() {
        return ResponseEntity.status(200).body(service.submitOnboarding());
    }
}

