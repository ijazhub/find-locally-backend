package com.findlocally.api.controller;

import com.findlocally.api.contract.MessagesApi;
import com.findlocally.api.service.MessagesService;
import com.findlocally.api.model.Conversation;
import com.findlocally.api.model.ConversationPage;
import com.findlocally.api.model.Message;
import java.time.OffsetDateTime;
import com.findlocally.api.model.SendMessageRequest;
import com.findlocally.api.model.StartConversationRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MessagesController implements MessagesApi {
    private final MessagesService service;

    public MessagesController(MessagesService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ConversationPage> listConversations(Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.listConversations(page, size));
    }

    @Override
    public ResponseEntity<List<Message>> listMessages(UUID conversationId, OffsetDateTime before, Integer size) {
        return ResponseEntity.status(200).body(service.listMessages(conversationId, before, size));
    }

    @Override
    public ResponseEntity<Void> markConversationRead(UUID conversationId) {
        service.markConversationRead(conversationId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<Message> sendMessage(UUID conversationId, SendMessageRequest sendMessageRequest) {
        return ResponseEntity.status(201).body(service.sendMessage(conversationId, sendMessageRequest));
    }

    @Override
    public ResponseEntity<Conversation> startConversation(StartConversationRequest startConversationRequest) {
        return ResponseEntity.status(200).body(service.startConversation(startConversationRequest));
    }
}

