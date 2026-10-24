package com.findlocally.api.storage;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3ObjectStorageTests {
    private final S3Client client = mock(S3Client.class);
    private final S3ObjectStorage storage = new S3ObjectStorage("test-bucket", () -> client);

    @Test
    void putsTheStreamWithBucketKeyMimeTypeLengthAndOverwriteProtection() throws Exception {
        byte[] bytes = {1, 2, 3};
        storage.upload("private/user/id", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertEquals("test-bucket", request.getValue().bucket());
        assertEquals("private/user/id", request.getValue().key());
        assertEquals("application/pdf", request.getValue().contentType());
        assertEquals(3L, request.getValue().contentLength());
        assertEquals("*", request.getValue().ifNoneMatch());
        try (var input = body.getValue().contentStreamProvider().newStream()) { assertArrayEquals(bytes, input.readAllBytes()); }
        verify(client).close();
    }

    @Test
    void missingBucketAndCredentialFailuresProduceServiceUnavailableWithoutLeakingSecrets() {
        var missing = new S3ObjectStorage("", () -> client);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> missing.upload("key", "image/jpeg", 1, new ByteArrayInputStream(new byte[1]))).getStatusCode());
        verifyNoInteractions(client);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("sensitive credential diagnostic"));
        var failure = assertThrows(ResponseStatusException.class,
                () -> storage.upload("key", "image/jpeg", 1, new ByteArrayInputStream(new byte[1])));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertFalse(failure.getReason().contains("sensitive"));
    }

    @Test
    void accessDeniedIsReportedAndCleanupTargetsOnlyTheSuppliedKey() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).requestId("test-request").message("sensitive service error").build());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> storage.upload("key", "image/jpeg", 1, new ByteArrayInputStream(new byte[1]))).getStatusCode());
        storage.delete("public/user/upload");
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-bucket").key("public/user/upload").build());
    }

    @Test
    void legacyUploadVerificationChecksExistenceAndDeclaredMetadata() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(3L).contentType("image/jpeg").build());
        assertDoesNotThrow(() -> storage.verify("key", "image/jpeg", 3));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> storage.verify("key", "image/jpeg", 4)).getStatusCode());
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> storage.verify("key", "image/jpeg", 3)).getStatusCode());
    }
}
