package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.BusinessOnboardingRepository;
import com.findlocally.api.repository.BusinessOnboardingRepository.Draft;
import com.findlocally.api.repository.BusinessOnboardingRepository.Upload;
import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessOnboardingServiceTests {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final BusinessOnboardingRepository repository = mock(BusinessOnboardingRepository.class);
    private final BusinessServicesRepository services = mock(BusinessServicesRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final BusinessServicesService serviceValidation =
            new BusinessServicesService(services, currentUser, VALIDATORS.getValidator());
    private final BusinessOnboardingService service = new BusinessOnboardingService(repository, services,
            serviceValidation, currentUser, VALIDATORS.getValidator(), "https://media.example.com/", mock(MediaService.class));
    private final UUID owner = UUID.randomUUID();
    private final UUID business = UUID.randomUUID();
    private final OnboardingState state = new OnboardingState().status(OnboardingState.StatusEnum.DRAFT)
            .currentStep(OnboardingState.CurrentStepEnum.ABOUT);

    BusinessOnboardingServiceTests() {
        when(currentUser.id()).thenReturn(owner);
        when(repository.find(owner, true)).thenReturn(new Draft(business, "DRAFT", state));
        when(repository.find(owner, false)).thenReturn(new Draft(business, "DRAFT", state));
        when(services.findAll(business)).thenReturn(List.of());
    }

    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    @Test
    void startUsesExistingBusinessAccountAndReturnsDraft() {
        when(repository.isBusinessAccount(owner)).thenReturn(true);
        when(repository.create(owner)).thenReturn(true);
        assertSame(state, service.startOnboarding());
        verify(repository).create(owner);
    }

    @Test
    void duplicateStartDoesNotChangeRolesOrDraft() {
        when(repository.isBusinessAccount(owner)).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, service::startOnboarding).getStatusCode());
        verify(repository, never()).find(any(),anyBoolean());
    }

    @Test
    void savesAboutAndAdvancesToFirstIncompleteStepWithoutDuplicates() {
        UUID category = UUID.randomUUID();
        when(repository.findCategory("makeup")).thenReturn(category);
        var about = new OnboardingAbout().ownerName("Asha").businessName("Asha Studio").category("makeup");
        service.saveOnboardingStep("ABOUT", about);
        service.saveOnboardingStep("ABOUT", about);
        assertEquals(List.of(OnboardingState.CompletedStepsEnum.ABOUT), state.getCompletedSteps());
        assertEquals(OnboardingState.CurrentStepEnum.LOCATION, state.getCurrentStep());
        verify(repository, times(2)).saveAbout(business, category, about);
    }

    @Test
    void rejectsUnknownCategoryAndMismatchedBodyBeforeWriting() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("ABOUT", new OnboardingWork())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("ABOUT",
                        new OnboardingAbout().ownerName("Asha").businessName("Studio").category("unknown"))).getStatusCode());
        verify(repository, never()).saveAbout(any(), any(), any());
        verify(repository, never()).saveProgress(any(), any());
    }

    @Test
    void rejectsInvalidServicesBeforeReplacingExistingServices() {
        var input = new OnboardingServices().services(List.of(new ServiceInput().name("Bad")
                .durationMinutes(4).price(new Money(100L, "INR"))));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("SERVICES", input)).getStatusCode());
        verify(services, never()).deleteAll(any());
    }

    @Test
    void rejectsReversedOrDuplicateAvailabilityBeforeWriting() {
        var day = new AvailabilityInputWeeklyInner().day(AvailabilityInputWeeklyInner.DayEnum.MON)
                .open(true).from("18:00").to("09:00");
        assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("AVAILABILITY", new AvailabilityInput().weekly(List.of(day))));
        day.from("09:00").to("18:00");
        assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("AVAILABILITY", new AvailabilityInput().weekly(List.of(day, day))));
        verify(repository, never()).saveAvailability(any(), any());
    }

    @Test
    void rejectsForeignOrIncompleteUploads() {
        var input = new OnboardingWork().avatarUploadId(UUID.randomUUID());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("WORK", input)).getStatusCode());
        verify(repository, never()).saveWork(any(), any(), any(), any(), any());
    }

    @Test
    void storesPublicMediaUrlsAndRetainsUploadIdsInDraft() {
        UUID uploadId = UUID.randomUUID();
        when(repository.findUpload(owner, uploadId)).thenReturn(new Upload(uploadId, "AVATAR", "avatars/my photo.png", "image/png"));
        var input = new OnboardingWork().avatarUploadId(uploadId);
        service.saveOnboardingStep("WORK", input);
        assertSame(input, state.getWork());
        verify(repository).saveWork(business, "https://media.example.com/avatars/my%20photo.png", null, List.of(), List.of());
    }

    @Test
    void incompleteSubmissionDoesNotPublish() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, service::submitOnboarding).getStatusCode());
        verify(repository, never()).publish(any());
    }

    @Test
    void submissionPublishesWithPendingVerification() {
        state.setCompletedSteps(Arrays.asList(OnboardingState.CompletedStepsEnum.values()));
        when(services.findAll(business)).thenReturn(List.of(new com.findlocally.api.model.Service().active(true)));
        assertEquals(OnboardingState.StatusEnum.SUBMITTED, service.submitOnboarding().getStatus());
        assertEquals(OnboardingState.CurrentStepEnum.DONE, state.getCurrentStep());
        verify(repository).publish(business);
        var order = inOrder(repository);
        order.verify(repository).publish(business);
        order.verify(repository).saveProgress(business, state);
        order.verifyNoMoreInteractions();
    }

    @Test
    void publishedDraftCannotBeEditedOrSubmittedAgain() {
        when(repository.find(owner, true)).thenReturn(new Draft(business, "ACTIVE", state));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.saveOnboardingStep("WORK", new OnboardingWork())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, service::submitOnboarding).getStatusCode());
        verify(repository, never()).saveProgress(any(), any());
    }
}
