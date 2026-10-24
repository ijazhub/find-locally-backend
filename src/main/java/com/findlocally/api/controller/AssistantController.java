package com.findlocally.api.controller;

import com.findlocally.api.contract.AssistantApi;
import com.findlocally.api.service.AssistantService;
import com.findlocally.api.model.AskAssistantRequest;
import com.findlocally.api.model.AssistantBookRequest;
import com.findlocally.api.model.AssistantReply;
import com.findlocally.api.model.Booking;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssistantController implements AssistantApi {
    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AssistantReply> askAssistant(AskAssistantRequest askAssistantRequest) {
        return ResponseEntity.status(200).body(service.askAssistant(askAssistantRequest));
    }

    @Override
    public ResponseEntity<Booking> assistantBook(UUID threadId, AssistantBookRequest assistantBookRequest) {
        return ResponseEntity.status(201).body(service.assistantBook(threadId, assistantBookRequest));
    }
}

