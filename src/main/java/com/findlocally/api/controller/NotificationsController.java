package com.findlocally.api.controller;

import com.findlocally.api.contract.NotificationsApi;
import com.findlocally.api.service.NotificationsService;
import com.findlocally.api.model.NotificationPage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NotificationsController implements NotificationsApi {
    private final NotificationsService service;

    public NotificationsController(NotificationsService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<NotificationPage> listNotifications(Boolean unreadOnly, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listNotifications(unreadOnly, page, size));
    }

    @Override
    public ResponseEntity<Void> markAllNotificationsRead() {
        service.markAllNotificationsRead();
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<Void> markNotificationRead(UUID notificationId) {
        service.markNotificationRead(notificationId);
        return ResponseEntity.status(204).build();
    }
}

