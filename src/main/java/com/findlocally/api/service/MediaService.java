package com.findlocally.api.service;

import com.findlocally.api.repository.MediaRepository;
import com.findlocally.api.model.*;
import com.findlocally.api.storage.ObjectStorage;
import com.findlocally.api.utility.*;
import java.util.*;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaService {
    @org.springframework.beans.factory.annotation.Autowired
    private MediaInspector inspector = new MediaInspector("ffprobe","ffmpeg",600,8192);
    @org.springframework.beans.factory.annotation.Value("${app.media.image-limit-mib:10}")
    private long imageLimit=10;
    @org.springframework.beans.factory.annotation.Value("${app.media.video-limit-mib:100}")
    private long videoLimit=100;
    private final MediaRepository repository;
    private final ObjectStorage storage;
    private final CurrentUser current;
    public MediaService(MediaRepository repository, ObjectStorage storage, CurrentUser current) {
        this.repository = repository; this.storage = storage; this.current = current;
    }
    @Transactional
    public UploadResponse createUpload(UploadPurpose uploadPurpose, MultipartFile file) {
        UUID user = current.id();
        if (uploadPurpose == null || file == null || file.isEmpty() || file.getContentType() == null)
            throw Inputs.bad("A purpose and a non-empty file with a content type are required");
        String purpose = uploadPurpose.getValue(), type = file.getContentType().toLowerCase(Locale.ROOT);
        long size = file.getSize();
        boolean video = purpose.equals("GALLERY_VIDEO"), document = purpose.equals("ID_DOCUMENT");
        boolean allowed = video ? Set.of("video/mp4", "video/webm").contains(type)
                : Set.of("image/jpeg", "image/png", "image/webp").contains(type) || (document && type.equals("application/pdf"));
        if (!allowed) throw Inputs.bad("Unsupported file content type for this purpose");
        if (size > (video ? videoLimit : imageLimit) * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Images and documents allow 10 MiB; videos allow 100 MiB");
        if (!purpose.equals("AVATAR") && !repository.isBusiness(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A BUSINESS account is required for business uploads");
        var inspection=inspector.inspect(file,document);
        UUID id = UUID.randomUUID();
        String key = (document ? "private/" : "public/") + user + "/" + id;
        repository.create(id, user, purpose, key, type, size);
        boolean uploaded = false;
        boolean cleanupRegistered = false;
        try (var content = file.getInputStream()) {
            storage.upload(key, type, size, content);
            uploaded = true;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) cleanup(key);
                    }
                });
                cleanupRegistered = true;
            }
            repository.complete(id);
            repository.inspected(id,inspection.state(),inspection.width(),inspection.height(),inspection.duration());
        } catch (IOException exception) {
            if (uploaded && !cleanupRegistered) cleanup(key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not read the uploaded file. Please retry.");
        } catch (RuntimeException exception) {
            if (uploaded && !cleanupRegistered) cleanup(key);
            throw exception;
        }
        return new UploadResponse().uploadId(id).status(UploadResponse.StatusEnum.UPLOADED).contentType(type).sizeBytes(size)
                .processingState(UploadResponse.ProcessingStateEnum.fromValue(inspection.state()))
                .width(inspection.width()).height(inspection.height()).durationSeconds(inspection.duration());
    }
    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (RuntimeException exception) {
            LoggerFactory.getLogger(MediaService.class).warn("S3 cleanup failed after an upload transaction rolled back; storage lifecycle cleanup may be needed");
        }
    }
    @Transactional
    public MediaRepository.Upload confirm(UUID user, UUID id, Set<String> purposes) {
        var upload = repository.lock(user, id);
        if (upload == null || !purposes.contains(upload.purpose())) throw Inputs.bad("Choose an owned upload with the correct purpose");
        if (!"UPLOADED".equals(upload.status())) throw Inputs.conflict("Finish upload transfer before attaching it");
        if ((purposes.contains("COVER") || purposes.contains("AVATAR")) && !repository.ready(user,id))
            throw Inputs.conflict("Image processing is not ready");
        return upload;
    }
}

