package com.findlocally.api.service;

import com.findlocally.api.repository.AssistantRepository;
import com.findlocally.api.model.AskAssistantRequest;
import com.findlocally.api.model.AssistantBookRequest;
import com.findlocally.api.model.AssistantReply;
import com.findlocally.api.model.Booking;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Business logic skeleton for the Assistant API. */
@Service
public class AssistantService {
    private final AssistantRepository repository;

    public AssistantService(AssistantRepository repository) {
        this.repository = repository;
    }

    public AssistantReply askAssistant(AskAssistantRequest askAssistantRequest) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "askAssistant is not implemented yet");
    }

    public Booking assistantBook(UUID threadId, AssistantBookRequest assistantBookRequest) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "assistantBook is not implemented yet");
    }
}

