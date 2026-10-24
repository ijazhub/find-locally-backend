package com.findlocally.api.service;

import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.utility.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BusinessAccess {
    private final CurrentUser currentUser;
    private final BusinessServicesRepository repository;
    public BusinessAccess(CurrentUser currentUser, BusinessServicesRepository repository) {
        this.currentUser = currentUser;
        this.repository = repository;
    }
    public UUID id() {
        UUID id = repository.findBusinessId(currentUser.id());
        if (id == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A business profile is required");
        return id;
    }
    public UUID lock() {
        UUID id = repository.lockBusiness(currentUser.id());
        if (id == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A business profile is required");
        return id;
    }
}
