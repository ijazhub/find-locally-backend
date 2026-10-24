package com.findlocally.api.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private static final Logger LOG = LoggerFactory.getLogger(S3ObjectStorage.class);
    private final String bucket;
    private final Supplier<S3Client> clients;
    @Autowired
    public S3ObjectStorage(@Value("${app.media.bucket:}") String bucket,
            @Value("${app.media.region:ap-south-1}") String region,
            @Value("${app.media.request-timeout-seconds:120}") long timeoutSeconds) {
        this(bucket, () -> S3Client.builder().region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(timeoutSeconds))
                        .apiCallAttemptTimeout(Duration.ofSeconds(timeoutSeconds))).build());
        if (region.isBlank() || timeoutSeconds < 1) throw new IllegalArgumentException("Configure a valid S3 region and positive request timeout");
    }
    S3ObjectStorage(String bucket, Supplier<S3Client> clients) { this.bucket = bucket; this.clients = clients; }
    private void configured() {
        if (bucket.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure app.media.bucket before uploading files");
    }
    @Override public void upload(String key, String contentType, long size, InputStream content) {
        configured();
        try (var client = clients.get()) {
            var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(size).ifNoneMatch("*").build();
            client.putObject(put, RequestBody.fromInputStream(content, size));
        } catch (S3Exception exception) { throw failure("upload", exception); }
        catch (SdkException exception) { throw failure("upload", exception); }
    }
    @Override public void delete(String key) {
        configured();
        try (var client = clients.get()) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException exception) { throw failure("cleanup", exception); }
    }
    @Override public void verify(String key, String contentType, long size) {
        configured();
        try (var client = clients.get()) {
            var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key)
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build());
            if (head.contentLength() != size || !contentType.equalsIgnoreCase(head.contentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file does not match its declared type and size");
            }
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish uploading the file before attaching it");
            throw failure("verification", exception);
        } catch (SdkException exception) { throw failure("verification", exception); }
    }
    private ResponseStatusException failure(String operation, SdkException exception) {
        if (exception instanceof S3Exception s3) {
            String code = s3.awsErrorDetails() == null ? "Unknown" : s3.awsErrorDetails().errorCode();
            // Never log request URLs, credentials, full SDK exception messages or document keys.
            LOG.warn("S3 {} failed: status={}, code={}, requestId={}", operation, s3.statusCode(), code, s3.requestId());
        } else {
            LOG.warn("S3 {} failed: {}. Check AWS_PROFILE, the extensionless AWS credentials file, workload credentials and network access.",
                    operation, exception.getClass().getSimpleName());
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3 operation failed. Check storage credentials, permissions and configuration.");
    }
}
