package com.autox.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link ObjectStorage} over the S3 protocol.
 *
 * <p>One implementation covers MinIO, AWS S3, Cloudflare R2 and the rest, because they
 * speak the same API. Swapping providers is an endpoint and a credential change.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ObjectStorage(S3Client client, S3Presigner presigner, StorageProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                // Signed in, so storage itself rejects an upload of a different size.
                .contentLength(contentLength)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());

        // The client must send back exactly the headers that were signed.
        return new PresignedUpload(
                presigned.url().toString(),
                "PUT",
                Map.of("Content-Type", contentType, "Content-Length", Long.toString(contentLength)),
                presigned.expiration() == null ? Instant.now().plus(ttl) : presigned.expiration(),
                contentLength);
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return Optional.of(new StoredObject(key, head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        } catch (S3Exception e) {
            // A HEAD on a missing key answers 404 without a typed exception on some
            // providers, so the status is checked too.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] read(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build()).asByteArray();
    }

    @Override
    public void write(String key, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<ObjectIdentifier> identifiers = new ArrayList<>(keys.size());
        keys.forEach(key -> identifiers.add(ObjectIdentifier.builder().key(key).build()));

        client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(properties.bucket())
                .delete(Delete.builder().objects(identifiers).quiet(true).build())
                .build());
    }

    @Override
    public String readUrl(String key) {
        if (properties.hasPublicBaseUrl()) {
            String base = properties.publicBaseUrl();
            return base.endsWith("/") ? base + key : base + "/" + key;
        }

        // Private bucket: hand out a signed, expiring URL instead.
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.readUrlTtl())
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .build());
        return presigned.url().toString();
    }

    @Override
    public void ensureReady() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            return;
        } catch (S3Exception missing) {
            // NoSuchBucketException is an S3Exception; a 403 can also mean "not yours or
            // not there", and either way the next step is the same.
            if (!properties.createBucketIfMissing()) {
                throw new IllegalStateException("Storage bucket '" + properties.bucket()
                        + "' is not reachable. Create it, or set autox.storage.create-bucket-if-missing"
                        + " for local development.", missing);
            }
        }

        log.info("Creating storage bucket '{}'", properties.bucket());
        client.createBucket(builder -> builder.bucket(properties.bucket()));
    }

    /** Only used for diagnostics and tests. */
    public URI endpoint() {
        return URI.create(properties.endpoint() == null ? "" : properties.endpoint());
    }
}
