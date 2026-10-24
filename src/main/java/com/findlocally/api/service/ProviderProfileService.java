package com.findlocally.api.service;

import com.findlocally.api.repository.*;
import com.findlocally.api.model.*;
import com.findlocally.api.utility.*;
import java.time.*;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class ProviderProfileService {
    private final ProviderProfileRepository repository;
    private final CatalogRepository catalog;
    private final CurrentUser current;
    private final Clock clock;
    public ProviderProfileService(ProviderProfileRepository repository, CatalogRepository catalog, CurrentUser current, Clock clock) {
        this.repository = repository; this.catalog = catalog; this.current = current; this.clock = clock;
    }
    @Transactional(readOnly = true) public ProviderSocialProfile getProviderSocialProfile(UUID id) {
        var profile = repository.profile(id, current.optionalId(), Year.now(clock).getValue());
        if (profile == null) throw Inputs.missing("Provider");
        return profile;
    }
    @Transactional(readOnly = true) public MediaPage listProviderMedia(UUID id, String type, Integer page, Integer size) {
        if (type != null && !java.util.Set.of("PHOTO", "VIDEO").contains(type)) throw Inputs.bad("type must be PHOTO or VIDEO");
        if (catalog.provider(id, current.optionalId()) == null) throw Inputs.missing("Provider");
        return repository.media(id, type, Pages.of(page, size));
    }
}

