package com.findlocally.api.controller;

import com.findlocally.api.contract.MediaApi;
import com.findlocally.api.service.MediaService;
import com.findlocally.api.model.UploadResponse;
import com.findlocally.api.model.UploadPurpose;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MediaController implements MediaApi {
    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<UploadResponse> createUpload(UploadPurpose purpose, MultipartFile file) {
        return ResponseEntity.status(201).body(service.createUpload(purpose, file));
    }
}

