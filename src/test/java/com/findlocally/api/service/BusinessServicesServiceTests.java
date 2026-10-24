package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessServicesServiceTests {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final BusinessServicesRepository repository = mock(BusinessServicesRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final BusinessServicesService service = new BusinessServicesService(repository, currentUser, VALIDATORS.getValidator());
    private final UUID owner = UUID.randomUUID();
    private final UUID business = UUID.randomUUID();

    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    private ServiceInput input() {
        return new ServiceInput().name("Makeup").durationMinutes(60)
                .price(new Money(50000L, "INR")).fulfilment(List.of(Fulfilment.AT_STUDIO));
    }

    private void signedIn() {
        when(currentUser.id()).thenReturn(owner);
        when(repository.findBusinessId(owner)).thenReturn(business);
        when(repository.lockBusiness(owner)).thenReturn(business);
    }

    @Test
    void rejectsMissingBusinessWithoutWriting() {
        when(currentUser.id()).thenReturn(owner);
        var error = assertThrows(ResponseStatusException.class, () -> service.createService(input()));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(repository, never()).create(any(), any());
    }

    @Test
    void rejectsInvalidServiceBeforeWriting() {
        signedIn();
        for (ServiceInput invalid : List.of(input().name(" "), input().durationMinutes(4),
                input().price(new Money(-1L, "INR")), input().price(new Money(0L, "XYZ")))) {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.createService(invalid)).getStatusCode());
        }
        verify(repository, never()).create(any(), any());
    }

    @Test
    void createUsesTheSignedInOwnersBusiness() {
        signedIn();
        var input = input();
        var expected = new com.findlocally.api.model.Service().id(UUID.randomUUID());
        when(repository.create(business, input)).thenReturn(expected);
        assertSame(expected, service.createService(input));
        verify(repository).lockBusiness(owner);
    }

    @Test
    void missingOrForeignServiceReturnsNotFoundForEveryMutation() {
        signedIn();
        UUID foreign = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.updateService(foreign, input())).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.setServiceActive(foreign, new SetServiceActiveRequest().active(false))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.deleteService(foreign)).getStatusCode());
        verify(repository).update(eq(business), eq(foreign), any());
        verify(repository).setActive(business, foreign, false);
        verify(repository).delete(business, foreign);
        verify(repository, times(3)).lockBusiness(owner);
    }

    @Test
    void listingServicesDoesNotAcquireAMutationLock() {
        signedIn();
        when(repository.findAll(business)).thenReturn(List.of());
        assertTrue(service.listOwnServices().isEmpty());
        verify(repository).findBusinessId(owner);
        verify(repository, never()).lockBusiness(any());
    }

    @Test
    void requiresExplicitActiveFlag() {
        signedIn();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setServiceActive(UUID.randomUUID(), new SetServiceActiveRequest())).getStatusCode());
        verify(repository, never()).setActive(any(), any(), anyBoolean());
    }
}
