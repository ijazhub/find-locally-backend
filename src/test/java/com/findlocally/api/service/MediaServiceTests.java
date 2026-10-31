package com.findlocally.api.service;

import com.findlocally.api.model.UploadPurpose;
import com.findlocally.api.model.UploadResponse;
import com.findlocally.api.repository.MediaRepository;
import com.findlocally.api.storage.ObjectStorage;
import com.findlocally.api.utility.CurrentUser;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaServiceTests {
    private final MediaRepository repository = mock(MediaRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final MediaService service = new MediaService(repository, storage, current);
    private final UUID user = UUID.randomUUID();

    @BeforeEach void setup() { when(current.id()).thenReturn(user); }

    @Test
    void streamsTheReceivedBytesAndOnlyCompletesAfterS3Success() {
        byte[] bytes = new byte[] {1, 2, 3, 4};
        doAnswer(call -> {
            assertArrayEquals(bytes, call.getArgument(3, InputStream.class).readAllBytes());
            verify(repository, never()).complete(any());
            return null;
        }).when(storage).upload(anyString(), eq("image/jpeg"), eq(4L), any(InputStream.class));
        var response = service.createUpload(UploadPurpose.AVATAR, new MockMultipartFile("file", "../../ignored.jpg", "image/jpeg", bytes));
        assertEquals(UploadResponse.StatusEnum.UPLOADED, response.getStatus());
        assertEquals(4L, response.getSizeBytes());
        String key = "public/" + user + "/" + response.getUploadId();
        var order = inOrder(repository, storage);
        order.verify(repository).create(response.getUploadId(), user, "AVATAR", key, "image/jpeg", 4L);
        order.verify(storage).upload(eq(key), eq("image/jpeg"), eq(4L), any(InputStream.class));
        order.verify(repository).complete(response.getUploadId());
    }

    @Test
    void identityDocumentsUsePrivateKeys() {
        when(repository.isBusiness(user)).thenReturn(true);
        var response = service.createUpload(UploadPurpose.ID_DOCUMENT, new MockMultipartFile("file", "id.pdf", "application/pdf", new byte[10]));
        verify(storage).upload(eq("private/" + user + "/" + response.getUploadId()), eq("application/pdf"), eq(10L), any(InputStream.class));
    }

    @Test
    void invalidFilesNeverReachStorageOrCreateMetadata() {
        assertThrows(ResponseStatusException.class, () -> service.createUpload(null, null));
        assertThrows(ResponseStatusException.class, () -> service.createUpload(UploadPurpose.AVATAR, new MockMultipartFile("file", new byte[0])));
        assertThrows(ResponseStatusException.class, () -> service.createUpload(UploadPurpose.AVATAR,
                new MockMultipartFile("file", "document.pdf", "application/pdf", new byte[10])));
        assertThrows(ResponseStatusException.class, () -> service.createUpload(UploadPurpose.GALLERY_VIDEO,
                new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[10])));
        for (var purpose : new UploadPurpose[] {UploadPurpose.AVATAR, UploadPurpose.ID_DOCUMENT, UploadPurpose.GALLERY_VIDEO}) {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn((purpose == UploadPurpose.GALLERY_VIDEO ? 100L : 10L) * 1024 * 1024 + 1);
            when(file.getContentType()).thenReturn(purpose == UploadPurpose.GALLERY_VIDEO ? "video/mp4" : "image/jpeg");
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ResponseStatusException.class,
                    () -> service.createUpload(purpose, file)).getStatusCode());
        }
        verifyNoInteractions(repository, storage);
    }

    @Test
    void storageAndInputFailuresNeverMarkAnUploadComplete() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(storage).upload(anyString(), anyString(), anyLong(), any());
        assertThrows(ResponseStatusException.class, () -> service.createUpload(UploadPurpose.AVATAR,
                new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[10])));
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.getContentType()).thenReturn("image/jpeg");
        when(unreadable.getSize()).thenReturn(10L);
        when(unreadable.getInputStream()).thenThrow(new IOException("read failure"));
        assertThrows(ResponseStatusException.class, () -> service.createUpload(UploadPurpose.AVATAR, unreadable));
        verify(repository, never()).complete(any());
    }

    @Test
    void cleanupWaitsForTheTransactionOutcomeAndRunsOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            var response = service.createUpload(UploadPurpose.AVATAR,
                    new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[10]));
            verify(storage, never()).delete(anyString());
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(storage).delete("public/" + user + "/" + response.getUploadId());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test
    void metadataFailureDeletesTheObjectUploadedByThisRequest() {
        doThrow(new IllegalStateException("database unavailable")).when(repository).complete(any());
        assertThrows(IllegalStateException.class, () -> service.createUpload(UploadPurpose.AVATAR,
                new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[10])));
        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(key.capture(), anyString(), anyLong(), any());
        verify(storage).delete(key.getValue());
    }
}
