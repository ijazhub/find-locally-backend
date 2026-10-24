package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.BusinessOnboardingRepository;
import com.findlocally.api.repository.BusinessOnboardingRepository.Draft;
import com.findlocally.api.repository.BusinessOnboardingRepository.Upload;
import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class BusinessOnboardingService {
    private static final List<String> STEPS = List.of("ABOUT", "LOCATION", "SERVICES", "AVAILABILITY", "WORK", "VERIFICATION");
    private final BusinessOnboardingRepository repository;
    private final BusinessServicesRepository services;
    private final BusinessServicesService serviceValidation;
    private final CurrentUser currentUser;
    private final Validator validator;
    private final String mediaBaseUrl;
    private final MediaService mediaService;

    public BusinessOnboardingService(BusinessOnboardingRepository repository, BusinessServicesRepository services,
            BusinessServicesService serviceValidation, CurrentUser currentUser, Validator validator,
            @Value("${app.media.public-base-url:}") String mediaBaseUrl, MediaService mediaService) {
        this.repository = repository;
        this.services = services;
        this.serviceValidation = serviceValidation;
        this.currentUser = currentUser;
        this.validator = validator;
        this.mediaBaseUrl = mediaBaseUrl;
        this.mediaService = mediaService;
    }

    @Transactional(readOnly = true)
    public OnboardingState getOnboarding() {
        return state(requireDraft(currentUser.id(), false));
    }

    @Transactional(readOnly = true)
    public ProviderDetail getOnboardingPreview() {
        Draft draft = requireDraft(currentUser.id(), false);
        var preview = repository.preview(draft.id());
        var offered = services.findAll(draft.id()).stream().filter(s -> Boolean.TRUE.equals(s.getActive())).toList();
        preview.setServices(offered);
        // Prices in different currencies cannot be ordered against one another.
        if (!offered.isEmpty() && offered.stream().map(s -> s.getPrice().getCurrency()).distinct().count() == 1) {
            preview.setStartingPrice(offered.stream().map(com.findlocally.api.model.Service::getPrice)
                    .min(Comparator.comparing(Money::getAmount)).orElse(null));
        }
        return preview;
    }

    @Transactional
    public OnboardingState startOnboarding() {
        UUID owner = currentUser.id();
        if (!repository.userExists(owner)) throw error(HttpStatus.UNAUTHORIZED, "User account not found");
        if (!repository.create(owner)) throw error(HttpStatus.CONFLICT, "Business onboarding already exists");
        repository.addBusinessRole(owner);
        return state(requireDraft(owner, false));
    }

    @Transactional
    public OnboardingState saveOnboardingStep(String step, SaveOnboardingStepRequest request) {
        UUID owner = currentUser.id();
        Draft draft = requireDraft(owner, true);
        requireEditable(draft);
        if (!STEPS.contains(step) || request == null || !validator.validate(request).isEmpty()) {
            throw badRequest("Invalid onboarding step or input");
        }
        var state = draft.state();
        switch (step) {
            case "ABOUT" -> {
                var input = body(request, OnboardingAbout.class);
                text(input.getOwnerName(), 120, "ownerName");
                text(input.getBusinessName(), 120, "businessName");
                text(input.getCategory(), 50, "category");
                UUID category = repository.findCategory(input.getCategory());
                if (category == null) throw badRequest("Unknown category");
                repository.saveAbout(draft.id(), category, input);
                state.setAbout(input);
            }
            case "LOCATION" -> {
                var input = body(request, OnboardingLocation.class);
                validateLocation(input);
                repository.saveLocation(draft.id(), input);
                state.setLocation(input);
            }
            case "SERVICES" -> {
                var input = body(request, OnboardingServices.class);
                validateServices(input);
                services.deleteAll(draft.id());
                input.getServices().forEach(service -> services.create(draft.id(), service));
                repository.saveBudget(draft.id(), input);
                state.setServices(input);
            }
            case "AVAILABILITY" -> {
                var input = body(request, AvailabilityInput.class);
                validateAvailability(input);
                repository.saveAvailability(draft.id(), input);
                state.setAvailability(input);
            }
            case "WORK" -> {
                var input = body(request, OnboardingWork.class);
                Upload avatar = upload(owner, input.getAvatarUploadId(), Set.of("AVATAR"));
                Upload cover = upload(owner, input.getCoverUploadId(), Set.of("COVER"));
                List<Upload> gallery = new ArrayList<>();
                Set<UUID> seen = new HashSet<>();
                if (input.getGalleryUploadIds() != null) {
                    for (UUID id : input.getGalleryUploadIds()) {
                        if (id == null || !seen.add(id)) throw badRequest("Gallery upload IDs must be unique and non-null");
                        gallery.add(upload(owner, id, Set.of("GALLERY_PHOTO", "GALLERY_VIDEO")));
                    }
                }
                repository.saveWork(draft.id(), publicUrl(avatar), publicUrl(cover),
                        gallery, gallery.stream().map(this::publicUrl).toList());
                state.setWork(input);
            }
            case "VERIFICATION" -> {
                var input = body(request, OnboardingVerification.class);
                if (input.getIdType() == null || input.getIdDocumentUploadId() == null) {
                    throw badRequest("idType and idDocumentUploadId are required");
                }
                upload(owner, input.getIdDocumentUploadId(), Set.of("ID_DOCUMENT"));
                repository.saveVerification(draft.id(), input);
                state.setVerification(input);
            }
            default -> throw badRequest("Unknown onboarding step");
        }
        var completed = new HashSet<>(state.getCompletedSteps());
        completed.add(OnboardingState.CompletedStepsEnum.fromValue(step));
        state.setCompletedSteps(STEPS.stream().map(OnboardingState.CompletedStepsEnum::fromValue)
                .filter(completed::contains).toList());
        String next = STEPS.stream().filter(s -> !completed.contains(OnboardingState.CompletedStepsEnum.fromValue(s)))
                .findFirst().orElse("DONE");
        state.setCurrentStep(OnboardingState.CurrentStepEnum.fromValue(next));
        repository.saveProgress(draft.id(), state);
        return state(draft);
    }

    @Transactional
    public OnboardingState submitOnboarding() {
        Draft draft = requireDraft(currentUser.id(), true);
        requireEditable(draft);
        if (draft.state().getCompletedSteps().size() != STEPS.size()) {
            throw badRequest("Complete all onboarding steps before submitting");
        }
        if (services.findAll(draft.id()).stream().noneMatch(s -> Boolean.TRUE.equals(s.getActive()))) {
            throw badRequest("Add at least one active service before submitting");
        }
        repository.publish(draft.id());
        draft.state().setStatus(OnboardingState.StatusEnum.SUBMITTED);
        draft.state().setCurrentStep(OnboardingState.CurrentStepEnum.DONE);
        repository.saveProgress(draft.id(), draft.state());
        repository.enqueueOnboarded(draft.id());
        return state(draft);
    }

    private OnboardingState state(Draft draft) {
        var state = draft.state();
        if (state.getServices() != null) {
            state.getServices().setServices(services.findAll(draft.id()).stream().map(service -> new ServiceInput()
                    .name(service.getName()).description(service.getDescription())
                    .durationMinutes(service.getDurationMinutes()).price(service.getPrice())
                    .fulfilment(service.getFulfilment())).toList());
        }
        return state;
    }

    private Draft requireDraft(UUID owner, boolean lock) {
        Draft draft = repository.find(owner, lock);
        if (draft == null) throw error(HttpStatus.NOT_FOUND, "Start business onboarding first");
        return draft;
    }

    private void requireEditable(Draft draft) {
        if (!"DRAFT".equals(draft.status())) throw error(HttpStatus.CONFLICT, "Published onboarding cannot be edited");
    }

    private <T> T body(SaveOnboardingStepRequest request, Class<T> type) {
        if (!type.isInstance(request)) throw badRequest("Request body does not match the onboarding step");
        return type.cast(request);
    }

    private void validateLocation(OnboardingLocation input) {
        text(input.getCity(), 100, "city");
        text(input.getNeighbourhood(), 100, "neighbourhood");
        if (input.getWorkMode() == null) throw badRequest("workMode is required");
        if (input.getServiceRadiusKm() != null
                && (input.getServiceRadiusKm().signum() < 0
                    || input.getServiceRadiusKm().compareTo(new java.math.BigDecimal("9999.9")) > 0)) {
            throw badRequest("serviceRadiusKm must be between 0 and 9999.9");
        }
        var point = input.getLocation();
        if (point != null && (point.getLat() == null || point.getLng() == null
                || !Double.isFinite(point.getLat()) || !Double.isFinite(point.getLng())
                || Math.abs(point.getLat()) > 90 || Math.abs(point.getLng()) > 180)) {
            throw badRequest("Invalid latitude or longitude");
        }
    }

    private void validateServices(OnboardingServices input) {
        if (input.getServices() == null || input.getServices().isEmpty()) throw badRequest("At least one service is required");
        input.getServices().forEach(serviceValidation::validateInput);
        var budget = input.getBudgetRange();
        if (budget == null) return;
        var min = budget.getMin();
        var max = budget.getMax();
        for (Money money : new Money[] {min, max}) {
            if (money != null && (money.getAmount() == null || money.getAmount() < 0 || !"INR".equals(money.getCurrency()))) {
                throw badRequest("Budget amounts must be non-negative INR amounts");
            }
        }
        if (min != null && max != null && min.getAmount() > max.getAmount()) {
            throw badRequest("Minimum budget must not exceed maximum budget");
        }
    }

    public void validateAvailability(AvailabilityInput input) {
        Set<Object> days = new HashSet<>();
        boolean hasOpenDay = false;
        if (input.getWeekly() != null) {
            for (var day : input.getWeekly()) {
                if (day == null || day.getDay() == null || !days.add(day.getDay())) {
                    throw badRequest("Weekly days must be present and unique");
                }
                if (!Boolean.FALSE.equals(day.getOpen())) {
                    try {
                        if (!LocalTime.parse(day.getFrom()).isBefore(LocalTime.parse(day.getTo()))) {
                            throw badRequest("Opening time must be before closing time");
                        }
                    } catch (java.time.format.DateTimeParseException | NullPointerException exception) {
                        throw badRequest("Open days need valid from and to times");
                    }
                    hasOpenDay = true;
                }
            }
        }
        if (!Boolean.TRUE.equals(input.getByAppointmentOnly()) && !hasOpenDay) {
            throw badRequest("Choose appointment-only availability or at least one open day");
        }
        if (input.getBlockedDates() != null && (input.getBlockedDates().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(input.getBlockedDates()).size() != input.getBlockedDates().size())) {
            throw badRequest("Blocked dates must be unique and non-null");
        }
    }

    private Upload upload(UUID owner, UUID id, Set<String> purposes) {
        if (id == null) return null;
        Upload upload = repository.findUpload(owner, id);
        if (upload == null) {
            mediaService.confirm(owner, id, purposes);
            upload = repository.findUpload(owner, id);
        }
        if (upload == null || !purposes.contains(upload.purpose())) {
            throw badRequest("Upload must be completed, owned by you, and have the correct purpose");
        }
        return upload;
    }

    private String publicUrl(Upload upload) {
        if (upload == null) return null;
        if (mediaBaseUrl.isBlank()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Configure app.media.public-base-url before attaching public media");
        }
        URI base;
        try {
            base = URI.create(mediaBaseUrl.endsWith("/") ? mediaBaseUrl : mediaBaseUrl + "/");
            if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null) {
                throw new IllegalArgumentException();
            }
            // Encode object keys as paths; never expose ID documents through this method.
            String path = new URI(null, null, "/" + upload.objectKey(), null).getRawPath().substring(1);
            URI resolved = base.resolve(path);
            if (!resolved.toString().startsWith(base.toString())) throw new IllegalArgumentException();
            return resolved.toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Invalid public media URL configuration or object key");
        }
    }

    private void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw badRequest("Invalid " + field);
    }

    private ResponseStatusException badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}

