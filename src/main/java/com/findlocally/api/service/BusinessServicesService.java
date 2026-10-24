package com.findlocally.api.service;

import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.model.Service;
import com.findlocally.api.model.ServiceInput;
import com.findlocally.api.model.SetServiceActiveRequest;
import com.findlocally.api.utility.CurrentUser;
import jakarta.validation.Validator;
import java.util.Currency;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class BusinessServicesService {
    private final BusinessServicesRepository repository;
    private final CurrentUser currentUser;
    private final Validator validator;

    public BusinessServicesService(BusinessServicesRepository repository, CurrentUser currentUser, Validator validator) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.validator = validator;
    }

    @Transactional
    public Service createService(ServiceInput input) {
        UUID businessId = requireBusiness(true);
        validateInput(input);
        return repository.create(businessId, input);
    }

    @Transactional
    public void deleteService(UUID serviceId) {
        if (repository.delete(requireBusiness(true), serviceId) == 0) throw notFound();
    }

    @Transactional(readOnly = true)
    public List<Service> listOwnServices() {
        return repository.findAll(requireBusiness(false));
    }

    @Transactional
    public Service setServiceActive(UUID serviceId, SetServiceActiveRequest request) {
        UUID businessId = requireBusiness(true);
        if (request == null || request.getActive() == null) throw badRequest("active is required");
        return requireService(repository.setActive(businessId, serviceId, request.getActive()));
    }

    @Transactional
    public Service updateService(UUID serviceId, ServiceInput input) {
        UUID businessId = requireBusiness(true);
        validateInput(input);
        return requireService(repository.update(businessId, serviceId, input));
    }

    public void validateInput(ServiceInput input) {
        if (input == null || !validator.validate(input).isEmpty()
                || input.getName() == null || input.getName().isBlank()
                || input.getDurationMinutes() == null || input.getDurationMinutes() < 5
                || input.getPrice() == null || input.getPrice().getAmount() == null
                || input.getPrice().getAmount() < 0
                || (input.getFulfilment() != null && input.getFulfilment().stream().anyMatch(java.util.Objects::isNull))) {
            throw badRequest("A service needs a name, duration of at least 5 minutes and a non-negative price");
        }
        try {
            String currency = input.getPrice().getCurrency();
            if (currency == null || currency.length() != 3) throw new IllegalArgumentException();
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Use a valid three-letter currency code");
        }
    }

    private UUID requireBusiness(boolean lock) {
        // Use the onboarding lock so replacing a draft's services and editing one service serialize.
        UUID id = lock ? repository.lockBusiness(currentUser.id()) : repository.findBusinessId(currentUser.id());
        if (id == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Start business onboarding first");
        return id;
    }

    private Service requireService(Service service) {
        if (service == null) throw notFound();
        return service;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

