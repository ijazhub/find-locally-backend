package com.findlocally.api.service;

import com.findlocally.api.model.NotificationPage;
import com.findlocally.api.repository.NotificationsRepository;
import com.findlocally.api.utility.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationsService {
    private final NotificationsRepository repository;
    private final CurrentUser current;
    public NotificationsService(NotificationsRepository repository, CurrentUser current) { this.repository = repository; this.current = current; }
    @Transactional(readOnly = true)
    public NotificationPage listNotifications(Boolean unreadOnly, Integer page, Integer size) {
        return repository.page(current.id(), Boolean.TRUE.equals(unreadOnly), Pages.of(page, size));
    }
    @Transactional public void markAllNotificationsRead() { repository.readAll(current.id()); }
    @Transactional public void markNotificationRead(UUID id) {
        if (repository.read(current.id(), id) == 0) throw Inputs.missing("Notification");
    }
}

