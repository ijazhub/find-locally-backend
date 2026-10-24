package com.findlocally.api.service;

import com.findlocally.api.model.ProviderPage;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedFollowingService {
    private final SavedFollowingRepository repository;
    private final CatalogRepository catalog;
    private final CurrentUser current;
    public SavedFollowingService(SavedFollowingRepository repository, CatalogRepository catalog, CurrentUser current) {
        this.repository = repository; this.catalog = catalog; this.current = current;
    }
    @Transactional public void followProvider(UUID id) { add(id, true); }
    @Transactional public void saveProvider(UUID id) { add(id, false); }
    @Transactional public void unfollowProvider(UUID id) { repository.remove(current.id(), id, true); }
    @Transactional public void unsaveProvider(UUID id) { repository.remove(current.id(), id, false); }
    @Transactional(readOnly = true) public ProviderPage listFollowing(Integer page, Integer size) {
        return repository.list(current.id(), null, true, Pages.of(page, size));
    }
    @Transactional(readOnly = true) public ProviderPage listSaved(String category, Integer page, Integer size) {
        if (category != null && category.length() > 50) throw Inputs.bad("Invalid category");
        return repository.list(current.id(), category == null || category.isBlank() ? null : category, false, Pages.of(page, size));
    }
    private void add(UUID id, boolean follow) {
        UUID user = current.id();
        if (catalog.provider(id, user) == null) throw Inputs.missing("Provider");
        repository.add(user, id, follow);
    }
}

